targetScope = 'subscription'

@description('Azure region for the staging resource group and backend resources.')
param location string = 'australiaeast'

@description('Azure region for Static Web Apps.')
param swaLocation string = 'eastasia'

@description('Environment name used to derive deterministic staging resource names.')
param environmentName string = 'staging'

@description('Log Analytics retention period.')
@minValue(30)
@maxValue(730)
param logRetentionInDays int = 30

@secure()
@description('Temporary secure value for the what-if-only Gemini key vault secret. Never use a placeholder in a real deployment.')
param googleApiKey string

@secure()
@description('Temporary secure value for the what-if-only Qdrant key vault secret. Never use a placeholder in a real deployment.')
param qdrantApiKey string

var resourceGroupName = 'azrg${uniqueString(subscription().id, location, environmentName)}'
var deploymentName = 'azdep${uniqueString(subscription().id, location, environmentName)}'

resource resourceGroup 'Microsoft.Resources/resourceGroups@2022-09-01' = {
  name: resourceGroupName
  location: location
  tags: {
    environment: environmentName
    purpose: 'AI-SDLC chatbot staging'
  }
}

module staging 'resources.bicep' = {
  name: deploymentName
  scope: resourceGroup
  params: {
    environmentName: environmentName
    location: location
    swaLocation: swaLocation
    logRetentionInDays: logRetentionInDays
    googleApiKey: googleApiKey
    qdrantApiKey: qdrantApiKey
  }
}

output resourceGroupName string = resourceGroup.name
output backendLocation string = location
output frontendLocation string = swaLocation
output staticWebAppUrl string = staging.outputs.staticWebAppUrl
output containerAppName string = staging.outputs.containerAppName
output containerRegistryName string = staging.outputs.containerRegistryName
output keyVaultName string = staging.outputs.keyVaultName
