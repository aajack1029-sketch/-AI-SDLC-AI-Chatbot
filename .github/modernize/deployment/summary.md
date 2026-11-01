# Staging Deployment Readiness Summary

## Artifacts created

- `demo/Dockerfile` and `demo/.dockerignore`: Java 17 multi-stage image, non-root runtime, JVM container memory settings, port 8080, and exclusions for local properties/env/build output.
- `demo/src/main/resources/application-staging.properties`: opt-in staging configuration, secure env placeholders for Gemini/Qdrant, Qdrant TLS/auth settings, and safe defaults disabling external schema initialization and startup ingestion.
- `my-ai-ui/public/staticwebapp.config.json` and `my-ai-ui/.env.example`: SPA fallback and documented Vite/Firebase build-time values, with a retained localhost API default.
- `infra/aca/staging.bicep`: existing-environment candidate revision template, multiple revision mode, stable 100% / candidate 0% traffic, secret parameters, internal ingress default, and TCP startup/readiness/liveness probes.
- `docs/deployment/staging.md`: required configuration, blockers, SWA build settings, ACA revision cutover/rollback guidance, and the safe next step.

The ignored local `demo/src/main/resources/application.properties`, Java application source, frontend behavior, and application data were not changed.

## Validation

- Backend Maven tests: passed, 33 tests, 0 failures/errors.
- Frontend Vite production build: passed.
- Frontend ESLint: passed.
- Local Docker image build: passed as `staging-backend:local`; not pushed or deployed.
- SWA configuration JSON: parsed successfully and copied into `my-ai-ui/dist`.
- Secret/config review: secrets are references or required environment inputs; no real secret values were added.
- Bicep compilation: not run; neither Azure CLI nor Bicep CLI is installed in this environment. No Azure login/resource operations were performed.

## Blockers

- The selected `demo/` controller hard-codes localhost CORS and accepts a client-supplied role; no server-side JWT authorization was found in the selected backend module. Public staging exposure is blocked until the application owner resolves and tests authentication/authorization and exact-origin CORS.
- The existing local application properties file is ignored/untracked. The deployment image excludes it and requires `SPRING_PROFILES_ACTIVE=staging`.
- Qdrant Cloud endpoint host, gRPC port, TLS setting, API key and staged collections/data are unknown.
- Azure region, existing ACA environment/app, image registry/reference, resource/scaling values, SWA origin/site and Firebase project settings are unknown and remain required operator inputs.

## Safe next step

Have the backend owner implement and test server-side Firebase/JWT verification, role authorization, and configurable exact-origin CORS in `demo/`; then provide the non-secret staging values and store API keys through the approved secret system. Review and compile the Bicep template before separately authorizing any staging deployment.

No Azure authentication, cloud resource creation/modification, deployment, publishing, or cost-incurring operation was performed.
