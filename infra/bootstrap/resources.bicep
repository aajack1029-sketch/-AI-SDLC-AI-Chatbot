targetScope = 'resourceGroup'

@description('Environment name used to derive deterministic resource names.')
param environmentName string

@description('Azure region for backend resources.')
param location string

@description('Azure region for the Static Web App.')
param swaLocation string

@description('Log Analytics retention period.')
@minValue(30)
@maxValue(730)
param logRetentionInDays int

@secure()
param googleApiKey string

@secure()
param qdrantApiKey string

var backendToken = uniqueString(subscription().id, resourceGroup().id, location, environmentName)
var frontendToken = uniqueString(subscription().id, resourceGroup().id, swaLocation, environmentName)
var appName = 'azca${backendToken}'
var staticWebAppName = 'azswa${frontendToken}1'
var appOrigin = 'https://${staticWebAppName}.azurestaticapps.net'
var acrPullRoleId = '7f951dda-4ed3-4680-a7ca-43fe172d538d'
var keyVaultSecretsUserRoleId = '4633458b-17de-408a-b874-0445c86b69e6'
var keyVaultSecretsOfficerRoleId = 'b86a8fe4-44ce-4948-aee5-eccb2c155cd7'

resource logAnalytics 'Microsoft.OperationalInsights/workspaces@2022-10-01' = {
  name: 'azlog${backendToken}'
  location: location
  properties: {
    sku: {
      name: 'PerGB2018'
    }
    retentionInDays: logRetentionInDays
    features: {
      enableLogAccessUsingOnlyResourcePermissions: true
    }
  }
}

resource registry 'Microsoft.ContainerRegistry/registries@2023-07-01' = {
  name: 'azacr${backendToken}'
  location: location
  sku: {
    name: 'Basic'
  }
  properties: {
    adminUserEnabled: false
    publicNetworkAccess: 'Enabled'
  }
}

resource appIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: 'azid${backendToken}'
  location: location
}

resource secretsOperatorIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: 'azsid${backendToken}'
  location: location
}

resource keyVault 'Microsoft.KeyVault/vaults@2023-07-01' = {
  name: 'azkv${backendToken}'
  location: location
  properties: {
    tenantId: tenant().tenantId
    sku: {
      family: 'A'
      name: 'standard'
    }
    enableRbacAuthorization: true
    enableSoftDelete: true
    softDeleteRetentionInDays: 7
    publicNetworkAccess: 'Enabled'
    networkAcls: {
      bypass: 'AzureServices'
      defaultAction: 'Allow'
    }
    accessPolicies: []
  }
}

// Role assignment resource names must be GUIDs as required by Azure RBAC.
resource acrPullAssignment 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(registry.id, appIdentity.id, acrPullRoleId)
  scope: registry
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', acrPullRoleId)
    principalId: appIdentity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource keyVaultSecretsOfficerAssignment 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(keyVault.id, secretsOperatorIdentity.id, keyVaultSecretsOfficerRoleId)
  scope: keyVault
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', keyVaultSecretsOfficerRoleId)
    principalId: secretsOperatorIdentity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource keyVaultSecretsUserAssignment 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(keyVault.id, appIdentity.id, keyVaultSecretsUserRoleId)
  scope: keyVault
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', keyVaultSecretsUserRoleId)
    principalId: appIdentity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource googleApiKeySecret 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = {
  parent: keyVault
  name: 'google-api-key'
  properties: {
    value: googleApiKey
  }
  dependsOn: [
    keyVaultSecretsOfficerAssignment
  ]
}

resource qdrantApiKeySecret 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = {
  parent: keyVault
  name: 'qdrant-api-key'
  properties: {
    value: qdrantApiKey
  }
  dependsOn: [
    keyVaultSecretsOfficerAssignment
  ]
}

resource containerAppsEnvironment 'Microsoft.App/managedEnvironments@2024-03-01' = {
  name: 'azcae${backendToken}'
  location: location
  properties: {
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: {
        customerId: logAnalytics.properties.customerId
        sharedKey: logAnalytics.listKeys().primarySharedKey
      }
    }
  }
}

resource containerApp 'Microsoft.App/containerApps@2024-03-01' = {
  name: appName
  location: location
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${appIdentity.id}': {}
    }
  }
  properties: {
    managedEnvironmentId: containerAppsEnvironment.id
    configuration: {
      activeRevisionsMode: 'Single'
      registries: [
        {
          server: registry.properties.loginServer
          identity: appIdentity.id
        }
      ]
      secrets: [
        {
          name: 'google-api-key'
          keyVaultUrl: googleApiKeySecret.properties.secretUriWithVersion
          identity: appIdentity.id
        }
        {
          name: 'qdrant-api-key'
          keyVaultUrl: qdrantApiKeySecret.properties.secretUriWithVersion
          identity: appIdentity.id
        }
      ]
      ingress: {
        external: false
        targetPort: 80
        transport: 'auto'
        allowInsecure: false
        corsPolicy: {
          allowedOrigins: [
            appOrigin
          ]
          allowedMethods: [
            'GET'
            'POST'
            'OPTIONS'
          ]
          allowedHeaders: [
            'Authorization'
            'Content-Type'
          ]
          allowCredentials: false
          maxAge: 300
        }
      }
    }
    template: {
      containers: [
        {
          name: 'bootstrap'
          image: 'mcr.microsoft.com/azuredocs/containerapps-helloworld:latest'
          resources: {
            cpu: json('0.5')
            memory: '1Gi'
          }
        }
      ]
      scale: {
        minReplicas: 0
        maxReplicas: 1
      }
    }
  }
  dependsOn: [
    acrPullAssignment
    keyVaultSecretsOfficerAssignment
    keyVaultSecretsUserAssignment
  ]
}

resource staticWebApp 'Microsoft.Web/staticSites@2022-09-01' = {
  name: staticWebAppName
  location: swaLocation
  sku: {
    name: 'Free'
    tier: 'Free'
  }
  properties: {
    buildProperties: {
      appLocation: 'my-ai-ui'
      apiLocation: ''
      outputLocation: 'dist'
    }
  }
}

output containerAppName string = containerApp.name
output containerAppsEnvironmentId string = containerAppsEnvironment.id
output containerRegistryName string = registry.name
output containerRegistryLoginServer string = registry.properties.loginServer
output keyVaultName string = keyVault.name
output appManagedIdentityClientId string = appIdentity.properties.clientId
output secretsOperatorIdentityClientId string = secretsOperatorIdentity.properties.clientId
output staticWebAppName string = staticWebApp.name
output staticWebAppUrl string = appOrigin
