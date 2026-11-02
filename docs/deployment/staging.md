# Staging deployment readiness

This guide prepares the repository for **staging only**. No Azure resources have been deployed, no image has been pushed, no SWA content has been published, and no Qdrant Cloud connection has been made. Do not apply the Bicep templates until the blockers below are resolved and a separate deployment is explicitly authorized.

## Chosen topology

- Backend: `demo/`, Java 17/Spring Boot, ACA ingress target port `8080`, external Qdrant Cloud.
- Frontend: `my-ai-ui/`, React/Vite built as SWA static content (`app_location: my-ai-ui`, `api_location: ""`, `output_location: dist`) in East Asia, as approved because Australia East is not listed for Static Web Apps.
- Revision deployment: ACA multiple-revision mode. Preserve the active stable revision at 100%, validate a new candidate at 0% via a revision label/test route, then shift traffic deliberately. Roll back by restoring the stable revision's traffic weight.
- External services: Gemini Developer API and Qdrant Cloud. GitHub MCP/synchronization remains disabled unless separately approved.
- Qdrant staging collection mapping: `chatbot-context-staging` is used for knowledge documents only (the current app does not persist chat history); `daily-report-embeddings-staging` stores daily reports; `github-sdlc-metadata-staging` stores GitHub events.

`infra/bootstrap/main.bicep` and `infra/bootstrap/resources.bicep` are the initial **bootstrap** templates. They create a generated resource group, Log Analytics workspace, Basic ACR, user-assigned identities, RBAC-enabled Key Vault, Consumption ACA environment, an internal hello-world placeholder Container App, and a Free SWA. They have compiled locally but have not been applied. The bootstrap Container App is not the chatbot; replace it with the actual application only in a separately reviewed deployment.

`infra/aca/staging.bicep` remains a parameterized **candidate revision** template for a later deployment to an existing Container Apps environment, registry, app and stable revision. It is not used for initial provisioning.

## Blockers before any public staging exposure

1. **Staging environment inputs are not yet available.** Obtain the Azure region, existing ACA environment/app and active stable revision, registry/image reference and pull permissions, resource/scaling values, exact SWA origin/site, Qdrant Cloud endpoint/gRPC port/TLS/API key, Firebase project and authorized domain, and Gemini model/key. These are operator inputs; no values are guessed.
2. **The Bicep template is a candidate-revision module, not initial provisioning.** It expects an existing ACA environment, app, stable revision, and registry. It leaves external ingress disabled by default; only enable it after reviewing the deployment plan and exact-origin CORS/security settings.
3. **The staging Qdrant dataset has not been provisioned.** The Qdrant Cloud cluster exists, but the three staging collections have not been created. The current local dataset contains mock knowledge and daily reports; local Qdrant points are not automatically copied to Qdrant Cloud. Create the named isolated collections with 3072-dimensional cosine vectors and access metadata, then seed only approved data. Do not point staging at production collections.
4. **The ignored local `demo/src/main/resources/application.properties` is not a deployable source of truth.** The tracked `application-staging.properties` supplies staging settings and must be activated with `SPRING_PROFILES_ACTIVE=staging`. The Docker context excludes the ignored local file so it cannot enter the image.
5. **Cloud-side validation remains outstanding.** The candidate Bicep compiles locally (Bicep CLI 0.48.1 in an official Azure CLI container), but Azure deployment `what-if`, ACR pull authorization, Qdrant Cloud connectivity, Firebase authorized domains, and end-to-end staging smoke tests have not run. The local deployment tools cannot access the subscription.
6. GitHub MCP/synchronization remains disabled. Enable it only after its container runtime requirements, PAT scope, repository, and operational need have been separately reviewed.

The backend now validates Firebase JWT signature, issuer, expiration, and audience, derives authorization from the verified `roles` claim, and allows one exact configured CORS origin. These controls passed the local security tests; keep ingress internal until the staging origin and API authentication have been verified in the target environment.

## Runtime configuration

Activate `application-staging.properties` in ACA with `SPRING_PROFILES_ACTIVE=staging`.

| Variable | Use | Handling |
|---|---|---|
| `GOOGLE_API_KEY` | Gemini chat and embeddings | Required secret; ACA secret reference / secure deployment input only |
| `GEMINI_CHAT_MODEL` | Gemini chat model | Non-secret; explicitly choose an available model |
| `JWT_ISSUER_URI` | Firebase ID token issuer | Required non-secret; must match the Firebase project |
| `FIREBASE_PROJECT_ID` | Firebase ID token audience | Required non-secret; must match the issuer's project |
| `FIREBASE_JWK_SET_URI` | Firebase signing keys | Optional non-secret; defaults to Firebase's official JWKS endpoint |
| `APP_CORS_ALLOWED_ORIGIN` | Exact staging frontend origin | Required non-secret; HTTPS origin only, no path or wildcard |
| `QDRANT_HOST` | Qdrant Cloud gRPC host, no scheme | Required non-secret; obtain from Qdrant Cloud |
| `QDRANT_PORT` | Qdrant Cloud gRPC port | Required non-secret; confirm for the endpoint |
| `QDRANT_API_KEY` | Qdrant authentication | Required secret; ACA secret reference / secure deployment input only |
| `QDRANT_USE_TLS` | Qdrant gRPC TLS | Required setting in the ACA template; confirm with provider configuration |
| `QDRANT_INIT_SCHEMA` | Collection/schema creation | Staging profile defaults to `false` to avoid unintended external mutations |
| `QDRANT_KNOWLEDGE_DOCS_COLLECTION`, `QDRANT_DAILY_REPORTS_COLLECTION`, `QDRANT_GITHUB_EVENTS_COLLECTION` | Qdrant collection names | Defaults mirror current application names; confirm the staging collections exist and contain expected dimensions/data |
| `APP_INGESTION_ENABLED`, `APP_INGESTION_LIMIT` | Startup ingestion | Defaults to `false` / `0`; do not enable until data ownership and duplicate-write behavior are approved |
| `GITHUB_SYNC_ENABLED` | GitHub background sync | Defaults to `false` |
| `GITHUB_PERSONAL_ACCESS_TOKEN` | Optional GitHub MCP | Secret only if GitHub MCP is explicitly enabled; image currently does not include Node/npm, so this optional integration is not ready in the container |
| `GITHUB_REPOSITORY_OWNER`, `GITHUB_REPOSITORY_NAME`, `GITHUB_MCP_COMMAND`, `GITHUB_MCP_PACKAGE`, `GITHUB_SYNC_INITIAL_DELAY_MS`, `GITHUB_SYNC_INTERVAL_MS`, `GITHUB_SYNC_PAGE_SIZE` | Optional GitHub MCP/sync | Non-secret settings; keep disabled until separately validated |

The staging profile uses port `8080`. `QDRANT_INIT_SCHEMA=false` and ingestion disabled protect an external collection from automatic setup/writes; the app may fail to start or serve queries if the expected collections are absent. Prepare/validate collections and data separately.

The checked-in mock daily-report dataset currently classifies its reports as `DEVELOPER`; it does not contain approved Manager-only business data. The local Qdrant dataset is not a staging import source. For staging, create the three named collections in the existing Qdrant Cloud cluster, seed only approved synthetic or sanitized data in a one-off controlled job, and verify every point's `role_access` before enabling the API. Keep normal ACA replicas at `APP_INGESTION_ENABLED=false` so scaling/restarts cannot trigger ingestion. Add Manager-labelled records only after their owner confirms the classification; never relabel Developer data as Manager merely to create test coverage.

Set the JWT issuer, audience, and exact CORS origin in the staging deployment environment; the ACA template exposes these as required parameters. Never pass Firebase ID tokens or user credentials as deployment parameters.

## Consumption profile and cost guardrail

The user's Azure CLI evidence reports an applicable ACA managed-environment quota of 1 in Australia East with current usage 0. The region is listed for `Microsoft.App/managedEnvironments`, and the user's authenticated workload-profile listing includes `Consumption` in Australia East:

```powershell
az containerapp env workload-profile list-supported --location australiaeast --output table
```

The deployment availability tools cannot currently access the user's subscription. Regional profile availability and quota are based on the user's authenticated CLI output.

Retail-price scenario as of 2026-11-01 (standard USD, before account discounts, tax, and currency conversion):

- ACR Basic registry unit: USD 0.1666/day, about USD 5.00 for 30 days, before storage or network overages.
- ACA Consumption: USD 0.000034 per active vCPU-second and USD 0.000004 per active GiB-second; the first 180,000 vCPU-seconds, 360,000 GiB-seconds, and 2 million requests per subscription/month are free.
- Azure Static Web Apps Free: USD 0 while within Free-tier limits.
- Azure Monitor/Log Analytics: first 5 GB of monthly ingestion is free at billing-account scope; overages are not included in the estimate.

The user confirmed the planning assumptions of 0-100 active replica-hours/month, no more than 2 million requests, logs at or below 5 GB/month, and an Azure-only USD 10 cap. For a single 0.5-vCPU/1-GiB backend replica, `minReplicas=0`, `maxReplicas=1`, no other ACA grant usage, and no billable egress, this estimates to about USD 5/month. About 150 active hours would cost USD 8.78; roughly 166 hours reaches USD 10. A 730-hour continuously active month is about USD 52.63 for ACR plus ACA after the grants. If another workload consumes the free grants, 150 active hours costs about USD 16.34 and exceeds the ceiling.

This is a conditional retail-price estimate, not a subscription-specific quote. Under the user's stated Azure-only usage assumptions, the estimated Azure spend is within USD 10/month, with about USD 5 nominal headroom. The candidate Bicep requires operator-selected CPU, memory, and replica limits. Gemini API and Qdrant Cloud charges are explicitly outside this Azure-only budget; log, network, and tax/FX costs may also apply. Keep the environment on Consumption and do not enable Dedicated profiles, private endpoints, or planned maintenance without a new cost review.

Pricing sources: [Azure Container Apps billing](https://learn.microsoft.com/en-us/azure/container-apps/billing), [Azure Container Apps retail prices](https://prices.azure.com/api/retail/prices?$filter=serviceName%20eq%20%27Azure%20Container%20Apps%27%20and%20armRegionName%20eq%20%27australiaeast%27&currencyCode=USD), [ACR Basic retail price](https://prices.azure.com/api/retail/prices?$filter=serviceName%20eq%20%27Container%20Registry%27%20and%20armRegionName%20eq%20%27australiaeast%27%20and%20skuName%20eq%20%27Basic%27%20and%20meterName%20eq%20%27Basic%20Registry%20Unit%27&currencyCode=USD), [Static Web Apps pricing](https://azure.microsoft.com/en-us/pricing/details/app-service/static/), [Azure Monitor pricing](https://azure.microsoft.com/en-us/pricing/details/monitor/).

## Bootstrap validation (what-if only)

The subscription-scope template derives a resource-group name using `uniqueString`; backend resources use `australiaeast`, and the SWA uses the approved `eastasia` region with a numeric suffix in its name. RBAC-enabled Key Vault is public-network enabled. ACR pull is assigned to the app's user-assigned identity before the Container App is created. Key Vault Secrets Officer is assigned to a separate secrets-operator identity; the app identity receives Key Vault Secrets User. Key Vault secret resources depend on the secrets-operator role assignment, and the Container App depends on the Key Vault role assignments. The ACA environment sends logs to the created Log Analytics workspace. Ingress is internal and CORS is restricted to the generated SWA origin.

The what-if script passes **synthetic placeholder strings** only for the two secure Key Vault secret parameters. Do not use the script for deployment or replace these values with actual secrets. A real deployment must source the values securely and must be separately authorized.

After signing in with Azure CLI and selecting the verified subscription, run this from the repository root:

```powershell
az account set --subscription aab79b74-5ccf-4d00-9206-f4bf882adefd
.\infra\bootstrap\what-if.ps1 -SubscriptionId aab79b74-5ccf-4d00-9206-f4bf882adefd
```

The script validates that the active subscription matches the supplied ID and runs only `az deployment sub what-if`; it does not deploy resources. The first local container attempt could compile Bicep but lacked a usable MSAL token. The user's Azure CLI session then reached the subscription, but Azure Policy rejected the what-if request because the session had not authenticated through MFA. No what-if result has yet been obtained and no resources were deployed. Reauthenticate interactively with MFA before retrying.

## Frontend build and SWA settings

Build the static site from `my-ai-ui/`; Vite emits `dist/` and copies `public/staticwebapp.config.json`. `VITE_CHAT_API_URL` must be the full HTTPS ACA API URL ending with `/api/v1/chat/query`. Build-time variables:

- `VITE_CHAT_API_URL`
- `VITE_FIREBASE_API_KEY`
- `VITE_FIREBASE_AUTH_DOMAIN`
- `VITE_FIREBASE_PROJECT_ID`
- `VITE_FIREBASE_APP_ID`

`VITE_*` values are included in browser assets. Firebase Web App configuration is public client configuration, not a location for service-account credentials or server keys. Store SWA build inputs in the approved pipeline/SWA configuration; do not commit real values. The existing Vite local fallback remains `http://localhost:8080/api/v1/chat/query`.

`staticwebapp.config.json` provides SPA navigation fallback while excluding static assets. It does not proxy the backend or bypass backend CORS/authentication. The repository has no SWA provisioning or deployment workflow yet; use a distinct staging SWA site/environment and build it with the staging API URL. ACA revision traffic switching covers the backend only, not atomic frontend/backend blue-green promotion.

## Build/check commands (local only)

Run from the repository root in an environment with the required Java/Maven and Node/npm versions:

```powershell
.\demo\mvnw.cmd -f .\demo\pom.xml test
Push-Location .\my-ai-ui
npm ci
npm run build
npm run lint
Pop-Location
```

Optional local container build (no push; only after Docker is locally available):

```powershell
Push-Location .\demo
docker build -t staging-backend:local .
Pop-Location
```

The Dockerfile build context excludes `application.properties`, environment files, and `target/`. A staging-profile container needs its runtime settings supplied as environment inputs. No Docker image was pushed or deployed.

## ACA revision cutover and rollback

Only perform these steps after a separately approved staging deployment and after all environment inputs and smoke-test criteria are reviewed:

1. Supply existing environment/app/registry/image identifiers, region, compute/scaling values, active stable revision name, Qdrant connection, and secure secret inputs to `infra/aca/staging.bicep`. Keep `externalIngress=false` until the backend security review approves public access. Use an immutable image tag or digest.
2. Confirm ACA is in multiple-revision mode. Keep traffic pinned to the named stable revision rather than a moving `latestRevision` rule.
3. Apply the candidate revision template only in an authorized deployment. It retains stable at 100% and sets candidate at 0%. Confirm the candidate is provisioned and its TCP probes pass.
4. Test candidate behavior through an approved revision-specific label/test route without shifting general ingress. TCP probes only establish that the process is listening; they do not prove Gemini/Qdrant health, API authorization, CORS, or end-to-end functionality.
5. Review logs and application-level smoke tests, then deliberately assign candidate 100% and stable 0% traffic. Monitor errors and user-visible behavior. Do not delete the stable revision during the observation window.
6. Roll back by restoring stable 100% and candidate 0%, then investigate the candidate. Keep both revisions until rollback is no longer needed.

Traffic weights and revision suffixes are operational inputs; the Bicep file is not a traffic-switching automation and this guide does not authorize or execute deployment.

## Safe next step

Obtain the non-secret Azure, SWA, Firebase, and Qdrant staging values; configure credentials in the approved secret system; create isolated staging collections and seed approved data. Then compile/review the Bicep template and perform a deployment what-if before separately authorizing any staging resource operation.
