param(
    [string]$BaseUrl = "http://localhost:8080",
    [string[]]$TestIds
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$testSetPath = Join-Path $projectRoot "src\main\resources\Data\ground_truth_testset.json"
$utf8 = [System.Text.UTF8Encoding]::new($false)
$testCases = [System.IO.File]::ReadAllText($testSetPath, $utf8) | ConvertFrom-Json
$unsupportedTestIds = @("TC-005", "TC-012", "TC-013")
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outputDirectory = Join-Path $projectRoot "target"
$outputPath = Join-Path $outputDirectory "ground-truth-results-$timestamp.json"
$results = [System.Collections.Generic.List[object]]::new()
$stopAfterProviderFailure = $false

if ($TestIds) {
    if ($TestIds.Count -eq 1 -and $TestIds[0].Contains(",")) {
        $TestIds = @($TestIds[0].Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    }
    $testCases = @($testCases | Where-Object { $_.test_id -in $TestIds })
    $missingIds = @($TestIds | Where-Object { $_ -notin @($testCases | ForEach-Object { $_.test_id }) })
    if ($missingIds.Count -gt 0) {
        throw "Unknown test ID(s): $($missingIds -join ', ')"
    }
}

foreach ($testCase in $testCases | Where-Object { $_.test_id -in $unsupportedTestIds }) {
    $results.Add([pscustomobject]@{
        test_id = $testCase.test_id
        category = $testCase.category
        query = $testCase.query
        expected_source = $testCase.expected_source
        ground_truth_answer = $testCase.ground_truth_answer
        actual_answer = $null
        result = "unsupported"
        http_status = $null
        duration_ms = $null
        error = "This PoC does not implement the required GitHub MCP, guardrails, or RBAC feature."
    })
}

foreach ($testCase in $testCases) {
    if ($testCase.test_id -in $unsupportedTestIds) {
        continue
    }

    if ($stopAfterProviderFailure) {
        $results.Add([pscustomobject]@{
            test_id = $testCase.test_id
            category = $testCase.category
            query = $testCase.query
            expected_source = $testCase.expected_source
            ground_truth_answer = $testCase.ground_truth_answer
            actual_answer = $null
            result = "not_run_after_provider_error"
            http_status = $null
            duration_ms = $null
            error = "Not run because a previous request returned HTTP 429 or 5xx."
        })
        continue
    }

    $body = @{
        message = $testCase.query
        role = "DEVELOPER"
    } | ConvertTo-Json -Compress
    $stopAfterThisCase = $false

    try {
        $timer = [System.Diagnostics.Stopwatch]::StartNew()
        $response = Invoke-RestMethod `
            -Uri "$($BaseUrl.TrimEnd('/'))/api/v1/chat/query" `
            -Method Post `
            -ContentType "application/json; charset=utf-8" `
            -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) `
            -TimeoutSec 120
        $timer.Stop()
        $results.Add([pscustomobject]@{
            test_id = $testCase.test_id
            category = $testCase.category
            query = $testCase.query
            expected_source = $testCase.expected_source
            ground_truth_answer = $testCase.ground_truth_answer
            actual_answer = $response.answer
            result = "manual_review"
            http_status = 200
            duration_ms = $timer.ElapsedMilliseconds
            error = $null
        })
        Write-Host "$($testCase.test_id): response received ($($timer.ElapsedMilliseconds) ms)"
    }
    catch {
        $statusCode = $null
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
        }

        $errorText = $_.ErrorDetails.Message
        if ([string]::IsNullOrWhiteSpace($errorText)) {
            $errorText = $_.Exception.Message
        }
        $results.Add([pscustomobject]@{
            test_id = $testCase.test_id
            category = $testCase.category
            query = $testCase.query
            expected_source = $testCase.expected_source
            ground_truth_answer = $testCase.ground_truth_answer
            actual_answer = $null
            result = "error"
            http_status = $statusCode
            duration_ms = $null
            error = $errorText
        })
        Write-Warning "$($testCase.test_id) failed (HTTP $statusCode): $errorText"
        if ($statusCode -eq 429 -or ($statusCode -ge 500 -and $statusCode -le 599)) {
            $stopAfterProviderFailure = $true
        }
    }
}

New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$report = [pscustomobject]@{
    generated_at = (Get-Date).ToString("o")
    base_url = $BaseUrl
    total_cases = $testCases.Count
    completed_responses = @($results | Where-Object { $_.result -eq "manual_review" }).Count
    unsupported_cases = @($results | Where-Object { $_.result -eq "unsupported" }).Count
    failed_cases = @($results | Where-Object { $_.result -eq "error" }).Count
    not_run_after_provider_error = @($results | Where-Object { $_.result -eq "not_run_after_provider_error" }).Count
    results = @($results)
}
$report | ConvertTo-Json -Depth 8 | Set-Content -Path $outputPath -Encoding UTF8
Write-Host "Saved benchmark responses to $outputPath"
Write-Host "Responses: $($report.completed_responses); unsupported: $($report.unsupported_cases); errors: $($report.failed_cases); not run after provider error: $($report.not_run_after_provider_error)"

if ($report.failed_cases -gt 0) {
    exit 1
}
