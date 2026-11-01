# Staging deployment readiness

This guide prepares the repository for **staging only**. No Azure resource operation, Azure login, image push, SWA publication, or Qdrant Cloud connection was performed as part of this work. Do not apply the Bicep template until the blockers below are resolved and a separate deployment is explicitly authorized.

## Chosen topology

- Backend: `demo/`, Java 17/Spring Boot, ACA ingress target port `8080`, external Qdrant Cloud.
- Frontend: `my-ai-ui/`, React/Vite built as SWA static content (`app_location: my-ai-ui`, `api_location: ""`, `output_location: dist`).
- Revision deployment: ACA multiple-revision mode. Preserve the active stable revision at 100%, validate a new candidate at 0% via a revision label/test route, then shift traffic deliberately. Roll back by restoring the stable revision's traffic weight.
- External services: Gemini Developer API and Qdrant Cloud. GitHub MCP/synchronization remains disabled unless separately approved.

`infra/aca/staging.bicep` is a parameterized **candidate revision** template for an existing Container Apps environment, registry, Container App and stable revision. It uses secure parameters for API keys, sets a zero-traffic candidate and leaves ingress internal by default. It is not a bootstrap template, and has not been applied or compiled in Azure.

## Blockers before any public staging exposure

1. **Backend authentication is not verified/enforced in the chosen `demo/` application.** `demo/src/main/java/com/example/demo/ChatController.java` accepts a caller-supplied role and has no authentication check. The inspected `demo/pom.xml` does not include Spring Security/resource-server dependencies. The frontend Firebase token does not by itself protect the backend. Do not expose this endpoint publicly until the application owner implements and tests server-side token verification/authorization.
2. **CORS is hard-coded.** The demo controller permits only `http://localhost:5173`; its `@CrossOrigin` does not read `APP_CORS_ALLOWED_ORIGIN`. A staging SWA origin will not work, and changing the property does not fix that. Keep ACA external ingress false until a narrow exact-origin CORS change has been reviewed and verified. This task intentionally does not modify Java source.
3. **The local `demo/src/main/resources/application.properties` is ignored/untracked.** It contains the local settings but is not a deployable source-of-truth file. The new tracked `application-staging.properties` supplies staging-only settings and must be activated with `SPRING_PROFILES_ACTIVE=staging`. The Docker context excludes the ignored local file so it cannot leak into an image.
4. **Qdrant Cloud details are missing.** Obtain the account's endpoint host, gRPC port, TLS requirement and API key. Confirm the selected Spring AI Qdrant starter/client version supports the configured `api-key`/`use-tls` properties before relying on them. This repository task does not test connectivity.
5. **No cloud location, existing ACA environment/app, ACR hostname/image, SWA origin/site, resource sizing, or scaling policy was provided.** They remain required Bicep/SWA operator inputs; no values are guessed. Bicep requires a currently active stable revision and is only suitable for a subsequent candidate revision.
6. Firebase frontend settings/project/authorized domain, JWT issuer/audience settings, Gemini key/model, and approval for any GitHub integration must be supplied/verified by the application owner. The JWT settings found in the ignored local properties file are not evidence that the `demo/` backend enforces JWT.

## Runtime configuration

Activate `application-staging.properties` in ACA with `SPRING_PROFILES_ACTIVE=staging`.

| Variable | Use | Handling |
|---|---|---|
| `GOOGLE_API_KEY` | Gemini chat and embeddings | Required secret; ACA secret reference / secure deployment input only |
| `GEMINI_CHAT_MODEL` | Gemini chat model | Non-secret; explicitly choose an available model |
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

The ignored local properties also contain `JWT_ISSUER_URI`, `FIREBASE_PROJECT_ID`, `FIREBASE_JWK_SET_URI`, and `APP_CORS_ALLOWED_ORIGIN`. Do not treat these as supported staging controls for `demo/` until the security/CORS implementation is reconciled. The current controller does not use the CORS property, and no JWT enforcement was found.

## Frontend build and SWA settings

Build the static site from `my-ai-ui/`; Vite emits `dist/` and copies `public/staticwebapp.config.json`. `VITE_CHAT_API_URL` must be the full HTTPS ACA API URL ending with `/api/v1/chat/query`. Build-time variables:

- `VITE_CHAT_API_URL`
- `VITE_FIREBASE_API_KEY`
- `VITE_FIREBASE_AUTH_DOMAIN`
- `VITE_FIREBASE_PROJECT_ID`
- `VITE_FIREBASE_APP_ID`

`VITE_*` values are included in browser assets. Firebase Web App configuration is public client configuration, not a location for service-account credentials or server keys. Store SWA build inputs in the approved pipeline/SWA configuration; do not commit real values. The existing Vite local fallback remains `http://localhost:8080/api/v1/chat/query`.

`staticwebapp.config.json` provides SPA navigation fallback while excluding static assets. It does not proxy the backend or bypass backend CORS/authentication.

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

Only perform these steps after a separately approved staging deployment and after the authentication/CORS blockers are resolved:

1. Supply existing environment/app/registry/image identifiers, region, compute/scaling values, active stable revision name, Qdrant connection, and secure secret inputs to `infra/aca/staging.bicep`. Keep `externalIngress=false` until the backend security review approves public access. Use an immutable image tag or digest.
2. Confirm ACA is in multiple-revision mode. Keep traffic pinned to the named stable revision rather than a moving `latestRevision` rule.
3. Apply the candidate revision template only in an authorized deployment. It retains stable at 100% and sets candidate at 0%. Confirm the candidate is provisioned and its TCP probes pass.
4. Test candidate behavior through an approved revision-specific label/test route without shifting general ingress. TCP probes only establish that the process is listening; they do not prove Gemini/Qdrant health, API authorization, CORS, or end-to-end functionality.
5. Review logs and application-level smoke tests, then deliberately assign candidate 100% and stable 0% traffic. Monitor errors and user-visible behavior. Do not delete the stable revision during the observation window.
6. Roll back by restoring stable 100% and candidate 0%, then investigate the candidate. Keep both revisions until rollback is no longer needed.

Traffic weights and revision suffixes are operational inputs; the Bicep file is not a traffic-switching automation and this guide does not authorize or execute deployment.

## Safe next step

Have the backend owner resolve and test server-side Firebase/JWT verification, role authorization, and an exact configurable SWA CORS origin in `demo/`. In parallel, obtain the non-secret ACA/SWA/Qdrant configuration values and place credentials in the organization's approved secret system. Re-run local builds and review the resulting source changes before separately authorizing any staging resource operation.
