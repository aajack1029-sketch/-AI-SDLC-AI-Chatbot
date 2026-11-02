param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-fA-F-]{36}$')]
    [string]$SubscriptionId
)

$ErrorActionPreference = 'Stop'

$azCommand = Get-Command az -ErrorAction SilentlyContinue
if (-not $azCommand) {
    throw 'Azure CLI (az) is required. Install it and sign in before running this preflight.'
}

$activeSubscriptionId = (& az account show --query id --output tsv)
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to read the active Azure subscription. Run az login, select the intended subscription, then retry.'
}

if ($activeSubscriptionId.Trim() -ne $SubscriptionId) {
    throw "Active subscription '$($activeSubscriptionId.Trim())' does not match the requested subscription."
}

$templatePath = Join-Path $PSScriptRoot 'main.bicep'
$parametersPath = Join-Path $PSScriptRoot 'main.parameters.json'

& az deployment sub what-if `
    --subscription $SubscriptionId `
    --location australiaeast `
    --template-file $templatePath `
    --parameters "@$parametersPath" `
        googleApiKey='what-if-only-placeholder' `
        qdrantApiKey='what-if-only-placeholder'

if ($LASTEXITCODE -ne 0) {
    throw "Azure deployment what-if failed with exit code $LASTEXITCODE."
}
