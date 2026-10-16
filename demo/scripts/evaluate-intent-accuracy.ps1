param(
    [string]$BaseUrl = "http://localhost:8080",
    [string[]]$TestIds,
    [string]$ModelLabel = "not specified",
    [ValidateRange(0, 300)]
    [int]$DelaySeconds = 20
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$testSetPath = Join-Path $projectRoot "src\main\resources\Data\intent_testset.json"
$utf8 = [System.Text.UTF8Encoding]::new($false)
$testCases = [System.IO.File]::ReadAllText($testSetPath, $utf8) | ConvertFrom-Json
if ($TestIds) {
    if ($TestIds.Count -eq 1 -and $TestIds[0].Contains(",")) {
        $TestIds = @($TestIds[0].Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    }
    $missingIds = @($TestIds | Where-Object { $_ -notin @($testCases | ForEach-Object { $_.test_id }) })
    if ($missingIds.Count -gt 0) {
        throw "Unknown test ID(s): $($missingIds -join ', ')"
    }
    $testCases = @($testCases | Where-Object { $_.test_id -in $TestIds })
}
$results = [System.Collections.Generic.List[object]]::new()
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outputPath = Join-Path (Join-Path $projectRoot "target") "intent-accuracy-$timestamp.json"

$caseNumber = 0
foreach ($testCase in $testCases) {
    if ($caseNumber -gt 0 -and $DelaySeconds -gt 0) {
        Start-Sleep -Seconds $DelaySeconds
    }
    $caseNumber++

    $body = @{
        message = $testCase.query
        role = "DEVELOPER"
    } | ConvertTo-Json -Compress

    try {
        $response = Invoke-RestMethod `
            -Uri "$($BaseUrl.TrimEnd('/'))/api/v1/chat/query" `
            -Method Post `
            -ContentType "application/json; charset=utf-8" `
            -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) `
            -TimeoutSec 120
        $actualIntent = [string]$response.intent
        $actualIntents = @($response.intents | ForEach-Object { [string]$_ } | Where-Object { $_ })
        if ($actualIntents.Count -eq 0 -and $actualIntent) {
            $actualIntents = @($actualIntent)
        }
        $expectedIntents = @($testCase.expected_intent)
        $isCorrect = $actualIntents.Count -eq $expectedIntents.Count `
            -and @($expectedIntents | Where-Object { $_ -notin $actualIntents }).Count -eq 0
        $results.Add([pscustomobject]@{
            test_id = $testCase.test_id
            query = $testCase.query
            expected_intent = $testCase.expected_intent
            actual_intent = $actualIntent
            actual_intents = $actualIntents
            correct = $isCorrect
            error = $null
        })
        $expectedLabel = $expectedIntents -join ", "
        $actualLabel = $actualIntents -join ", "
        Write-Host "$($testCase.test_id): expected [$expectedLabel], got [$actualLabel]"
    }
    catch {
        $results.Add([pscustomobject]@{
            test_id = $testCase.test_id
            query = $testCase.query
            expected_intent = $testCase.expected_intent
            actual_intent = $null
            actual_intents = @()
            correct = $false
            error = $_.Exception.Message
        })
        Write-Warning "$($testCase.test_id) failed: $($_.Exception.Message)"
    }
}

$correctCount = @($results | Where-Object { $_.correct }).Count
$accuracy = if ($results.Count -eq 0) { 0 } else { [math]::Round(100.0 * $correctCount / $results.Count, 2) }
$report = [pscustomobject]@{
    generated_at = (Get-Date).ToString("o")
    base_url = $BaseUrl
    model_label = $ModelLabel
    total_cases = $results.Count
    correct_cases = $correctCount
    accuracy_percent = $accuracy
    results = @($results)
}
New-Item -ItemType Directory -Path (Split-Path -Parent $outputPath) -Force | Out-Null
$report | ConvertTo-Json -Depth 8 | Set-Content -Path $outputPath -Encoding UTF8
Write-Host "Intent accuracy: $correctCount/$($results.Count) ($accuracy%)"
Write-Host "Model label: $ModelLabel"
Write-Host "Saved intent benchmark results to $outputPath"

if (@($results | Where-Object { $_.error }).Count -gt 0) {
    exit 1
}
