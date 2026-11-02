// Staging revision template for an EXISTING ACA environment and registry.
// This module creates/updates only the Container App; it does not create an
// environment, registry, Key Vault, Firebase project, or Qdrant Cloud cluster.
// Supply all required values through a protected parameter file/secret pipeline.
targetScope = 'resourceGroup'

@description('Existing Container Apps environment resource ID.')
param containerAppsEnvironmentId string

@description('Existing Container App name.')
param containerAppName string

@description('Azure region of the existing Container App environment.')
param location string

@description('Existing registry hostname, for example a registry FQDN (no scheme).')
param registryServer string

@description('Immutable backend image reference including a unique tag or digest.')
param image string

@description('New candidate revision suffix.')
param revisionSuffix string

@description('Currently active stable revision name, which will retain 100% traffic.')
param stableRevisionName string

@description('Keep false until the endpoint, token authorization, and exact-origin CORS are verified and staging exposure is approved.')
param externalIngress bool = false

@description('Container CPU value as JSON numeric text, e.g. a value selected by the operator.')
param containerCpu string

@description('Container memory limit selected by the operator, with ACA units.')
param containerMemory string

@description('Minimum replicas selected by the operator.')
param minReplicas int

@description('Maximum replicas selected by the operator.')
param maxReplicas int

@description('Qdrant Cloud gRPC host from the Qdrant account; do not include a scheme.')
param qdrantHost string

@description('Qdrant Cloud gRPC port confirmed in the Qdrant account.')
param qdrantPort int

@description('Whether the selected Qdrant Cloud endpoint requires TLS.')
param qdrantUseTls bool

@description('Gemini chat model approved for staging.')
param geminiChatModel string

@description('Firebase project ID used as the JWT audience.')
param firebaseProjectId string

@description('Firebase ID token issuer URL for the selected project.')
param jwtIssuerUri string

@description('Exact HTTPS origin of the staging frontend; no path or wildcard.')
param appCorsAllowedOrigin string

@secure()
@description('Gemini Developer API key; passed as a secure deployment parameter.')
param googleApiKey string

@secure()
@description('Qdrant Cloud API key; passed as a secure deployment parameter.')
param qdrantApiKey string

@description('Staging collection for the knowledge documents.')
param knowledgeDocsCollection string = 'chatbot-context-staging'

@description('Staging collection for daily reports.')
param dailyReportsCollection string = 'daily-report-embeddings-staging'

@description('Staging collection for GitHub SDLC events.')
param githubEventsCollection string = 'github-sdlc-metadata-staging'

resource app 'Microsoft.App/containerApps@2024-03-01' = {
  name: containerAppName
  location: location
  tags: {
    environment: 'staging'
  }
  identity: {
    type: 'SystemAssigned'
  }
  properties: {
    managedEnvironmentId: containerAppsEnvironmentId
    configuration: {
      activeRevisionsMode: 'Multiple'
      registries: [
        {
          server: registryServer
          identity: 'system'
        }
      ]
      secrets: [
        {
          name: 'google-api-key'
          value: googleApiKey
        }
        {
          name: 'qdrant-api-key'
          value: qdrantApiKey
        }
      ]
      ingress: {
        external: externalIngress
        targetPort: 8080
        transport: 'auto'
        allowInsecure: false
        traffic: [
          {
            revisionName: stableRevisionName
            weight: 100
          }
          {
            revisionName: '${containerAppName}--${revisionSuffix}'
            weight: 0
          }
        ]
      }
    }
    template: {
      revisionSuffix: revisionSuffix
      containers: [
        {
          name: 'backend'
          image: image
          resources: {
            cpu: json(containerCpu)
            memory: containerMemory
          }
          env: [
            {
              name: 'SPRING_PROFILES_ACTIVE'
              value: 'staging'
            }
            {
              name: 'GOOGLE_API_KEY'
              secretRef: 'google-api-key'
            }
            {
              name: 'QDRANT_HOST'
              value: qdrantHost
            }
            {
              name: 'QDRANT_PORT'
              value: string(qdrantPort)
            }
            {
              name: 'QDRANT_API_KEY'
              secretRef: 'qdrant-api-key'
            }
            {
              name: 'QDRANT_USE_TLS'
              value: string(qdrantUseTls)
            }
            {
              name: 'QDRANT_INIT_SCHEMA'
              value: 'false'
            }
            {
              name: 'QDRANT_KNOWLEDGE_DOCS_COLLECTION'
              value: knowledgeDocsCollection
            }
            {
              name: 'QDRANT_DAILY_REPORTS_COLLECTION'
              value: dailyReportsCollection
            }
            {
              name: 'QDRANT_GITHUB_EVENTS_COLLECTION'
              value: githubEventsCollection
            }
            {
              name: 'APP_INGESTION_ENABLED'
              value: 'false'
            }
            {
              name: 'APP_INGESTION_LIMIT'
              value: '0'
            }
            {
              name: 'GITHUB_SYNC_ENABLED'
              value: 'false'
            }
            {
              name: 'GEMINI_CHAT_MODEL'
              value: geminiChatModel
            }
            {
              name: 'FIREBASE_PROJECT_ID'
              value: firebaseProjectId
            }
            {
              name: 'JWT_ISSUER_URI'
              value: jwtIssuerUri
            }
            {
              name: 'APP_CORS_ALLOWED_ORIGIN'
              value: appCorsAllowedOrigin
            }
          ]
          probes: [
            {
              type: 'Startup'
              tcpSocket: {
                port: 8080
              }
              periodSeconds: 10
              timeoutSeconds: 5
              failureThreshold: 30
            }
            {
              type: 'Liveness'
              tcpSocket: {
                port: 8080
              }
              periodSeconds: 30
              timeoutSeconds: 5
              failureThreshold: 3
            }
            {
              type: 'Readiness'
              tcpSocket: {
                port: 8080
              }
              periodSeconds: 10
              timeoutSeconds: 5
              failureThreshold: 3
            }
          ]
        }
      ]
      scale: {
        minReplicas: minReplicas
        maxReplicas: maxReplicas
      }
    }
  }
}
