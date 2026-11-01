# Staging Deployment Readiness Plan

## Goal and boundaries

Prepare repository-owned deployment files for a **staging** topology consisting of:

- The existing Spring Boot application in `demo/` on Azure Container Apps (ACA), with multiple revisions enabled so staging traffic can be shifted and rolled back by revision.
- The existing React/Vite application in `my-ai-ui/` built for Azure Static Web Apps (SWA).
- Qdrant Cloud as an external managed dependency; do not provision or connect to a Qdrant service in this task.

This is repository preparation only. Do not log in to Azure, create/update/delete Azure or third-party resources, deploy, publish, push images, run a cloud workflow, or incur costs. Do not modify business logic or data. No secrets may be written to tracked files.

## Repository findings and chosen application

The root `README.md` identifies `demo/` as the current backend paired with `my-ai-ui/`. The repository also contains a separate root-level Java application and older root frontend/configuration artifacts. Keep this deployment setup scoped to `demo/` and `my-ai-ui/`; do not change the root-level application or the unrelated `node - project/`, Firebase Admin, data, scripts, or local Qdrant storage.

- Backend: `demo/` Spring Boot, Maven, Java 17, port 8080; API route `POST /api/v1/chat/query`. The inspected controller hard-codes CORS to `http://localhost:5173` and accepts a client-supplied role. Although the properties file has JWT-related settings, `demo/pom.xml` has no Spring Security/resource-server dependency and no security filter configuration was found. Treat public staging exposure as blocked until the application owner resolves this security mismatch.
- External services: Gemini Developer API (`GOOGLE_API_KEY`); Qdrant gRPC endpoint (`QDRANT_HOST`, `QDRANT_PORT`, collections and schema initialization); optional GitHub MCP token/repository/sync settings.
- Frontend: `my-ai-ui/` React 19, TypeScript, Vite; `VITE_CHAT_API_URL` and four Firebase web-app settings are read at build time. Keep Vite's existing localhost fallback for local development.
- Deployment-sensitive uncertainty: Azure region, registry/image reference, ACA environment and app names, SWA site/domain, resource sizing/scaling, the Qdrant Cloud host/port/API key/TLS requirements, Google API key, Firebase project details/JWT issuer, and allowed staging origin are not known. Leave these as explicit operator inputs and blockers; do not infer them from local defaults.

## Scope and artifacts to create or adjust

1. `demo/Dockerfile`
   - Multi-stage Java 17 build and small Java 17 runtime based on official Eclipse Temurin images.
   - Build only the `demo/` Spring Boot service; run as an unprivileged user, expose 8080, support container-aware JVM options, and avoid packaging secrets/data.
   - Add `demo/.dockerignore` to exclude ignored local properties, env files and build output.
2. `demo/src/main/resources/application-staging.properties`
   - Add a separate, opt-in `staging` profile without changing the ignored local `application.properties`.
   - Configure Qdrant Cloud API-key/TLS settings supported by the Spring AI Qdrant starter, require Qdrant host/port/key at runtime, and default TLS on only for the staging profile.
   - Disable schema initialization and startup ingestion by default for staging to avoid unapproved external data writes.
   - Do not claim that `APP_CORS_ALLOWED_ORIGIN` changes the current controller's hard-coded CORS annotation; do not modify Java source. Document that a restricted staging origin requires a narrowly-scoped application change before deployment.
   - Do not enable startup ingestion by default for staging or create/migrate cloud collections automatically without an explicit decision; preserve the local default if staging can explicitly override it.
3. `demo` backend health support/configuration
   - Use ACA TCP probes on port 8080 if the app has no authenticated-safe health endpoint. Do not add a dependency or alter Java business/security code just to fabricate a probe endpoint. Record that TCP probes confirm the process is listening, not downstream Gemini/Qdrant health.
4. `my-ai-ui/public/staticwebapp.config.json`
   - Configure SPA navigation fallback without rewriting static assets or `/api` requests.
   - Avoid broad allow-origin rules or exposing secrets.
5. `my-ai-ui/.env.example`
   - Document build-time inputs `VITE_CHAT_API_URL`, `VITE_FIREBASE_API_KEY`, `VITE_FIREBASE_AUTH_DOMAIN`, `VITE_FIREBASE_PROJECT_ID`, and `VITE_FIREBASE_APP_ID`.
   - Mark Vite variables as public browser configuration; never put API tokens, Firebase Admin credentials, or backend secrets here.
6. `infra/aca/staging.bicep`
   - Parameterize existing Container Apps environment, app/registry/image, location, resource/scaling settings, Qdrant endpoint, secure API keys, and an existing stable revision.
   - Set ACA multiple-revision mode, HTTPS-only ingress to 8080, startup/liveness/readiness TCP probes, and a 0%-traffic candidate while a specified stable revision remains at 100%.
   - Keep external ingress false by default because the selected backend currently lacks server-side authentication and staging-origin CORS.
   - Use secure parameters/ACA secret references for secret values. Never provide a registry, region, compute size, endpoint, or credential default.
   - This is a candidate-revision template only, not a first-deployment template, and must not be applied until blockers are resolved.
7. `docs/deployment/staging.md`
   - List the runtime/build inputs and blockers, build/test steps, no-deploy validation performed, ACA revision blue/green procedure and rollback, SWA build settings, Qdrant Cloud requirements, and a safe next step. Clearly distinguish candidate validation from actual traffic cutover.
8. `.github/modernize/deployment/plan.md`, `.github/modernize/deployment/progress.md`, and `.github/modernize/deployment/summary.md` to track and report this work.

## Required configuration inputs

### ACA backend runtime

- Required secret supplied through ACA secret references or secure deployment inputs: `GOOGLE_API_KEY` and `QDRANT_API_KEY` (the latter only if supported by the pinned client). Optional `GITHUB_PERSONAL_ACCESS_TOKEN` is needed only if GitHub MCP is deliberately enabled.
- Required non-secret values: `QDRANT_HOST` (Qdrant Cloud endpoint host, without guessed value), `QDRANT_PORT` and TLS setting confirmed by its account/settings, exact staging SWA origin (currently not configurable in `demo` controller), Firebase project/audience and JWK URI as appropriate. `JWT_ISSUER_URI` exists in demo properties but is not enforced by the inspected demo application.
- Explicit staging decisions: `QDRANT_INIT_SCHEMA`, `APP_INGESTION_ENABLED`, `APP_INGESTION_LIMIT`, collection names, `GEMINI_CHAT_MODEL`; optional `GITHUB_MCP_COMMAND`, `GITHUB_MCP_PACKAGE`, `GITHUB_PERSONAL_ACCESS_TOKEN`, `GITHUB_REPOSITORY_OWNER`, `GITHUB_REPOSITORY_NAME`, `GITHUB_SYNC_ENABLED`, `GITHUB_SYNC_INITIAL_DELAY_MS`, `GITHUB_SYNC_INTERVAL_MS`, and `GITHUB_SYNC_PAGE_SIZE`.
- ACA ingress must use HTTPS externally and route to container port 8080. No region, CPU/memory, minimum replicas, maximum replicas, registry, or image tag is selected in advance.

### SWA frontend build

- `VITE_CHAT_API_URL` must be the full HTTPS backend endpoint ending in `/api/v1/chat/query`.
- `VITE_FIREBASE_API_KEY`, `VITE_FIREBASE_AUTH_DOMAIN`, `VITE_FIREBASE_PROJECT_ID`, and `VITE_FIREBASE_APP_ID` are Firebase Web App config compiled into the browser bundle, not server secrets. Set the approved Firebase authorized domains and staging identity configuration separately.
- Build app location: `my-ai-ui`; output location: `dist`; API location: none.

## Execution and validation steps

1. Complete repository analysis and save this plan and initial progress before deployment-file edits.
2. Prepare a dedicated version-control branch through the prescribed version-control tool (no direct Git commands).
3. Read this plan and progress file again before changing files.
4. Inspect existing backend/frontend config and use Dockerfile-generation or ACA guidance tools where useful; do not invoke any resource/deployment tools.
5. Create only the scoped artifacts listed above, leaving unknown values parameterized and documenting any unsafe or unsupported assumptions as blockers.
6. Validate without Azure or external resource access:
   - `demo/mvnw.cmd -f demo/pom.xml test` (or repository-supported Maven wrapper equivalent).
   - `npm run build` and `npm run lint` in `my-ai-ui/`.
   - Check Dockerfile/ACA/SWA configuration for valid syntax and unresolved placeholders. A local Docker build may download public base images/dependencies and create only a local image; never push it, authenticate to Azure, or create cloud resources.
   - Confirm no application data or secrets were added.
7. Update progress after each phase, write the deployment summary, then commit the repository changes using the prescribed version-control tool.

## Explicit non-actions

No Azure authentication, resource creation/update, deployment, static-site publishing, image build/push, cloud connectivity test, Qdrant provisioning, or cost estimate is authorized by this task.
