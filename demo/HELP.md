# Getting Started

### Run locally

Set `GOOGLE_API_KEY` to a Gemini API key from Google AI Studio, then start the
application from this directory. The Google GenAI chat and embedding clients use
the Gemini Developer API; embeddings call its REST endpoint directly because
Spring AI 2.0.1 does not correctly map the single-embedding REST response. They
do not use Vertex AI project credentials.

```powershell
$env:GOOGLE_API_KEY = "your Gemini API key"
$env:APP_INGESTION_ENABLED = "true"
$env:APP_INGESTION_LIMIT = "0"
.\mvnw.cmd spring-boot:run
```

Use the Qdrant container `qdrant-dev-v1-18` (REST port `6333`, gRPC port
`6334`). Start it if it is stopped:

```powershell
docker start qdrant-dev-v1-18
```

The default `APP_INGESTION_ENABLED=true` imports reports on startup;
`APP_INGESTION_LIMIT=0` imports all reports. Ingestion uses deterministic point
IDs, so repeating it updates the same report points but still spends embedding
API requests. To run the ground-truth queries against the existing 70 points
without re-embedding them, start the app in one PowerShell window with:

```powershell
$env:GOOGLE_API_KEY = "your Gemini API key"
$env:APP_INGESTION_ENABLED = "false"
.\mvnw.cmd spring-boot:run
```

With the app running, open another PowerShell window in this directory and
execute the 17 supported RAG/out-of-domain cases:

```powershell
.\scripts\evaluate-ground-truth.ps1
```

The runner saves each response and its ground-truth answer to
`target/ground-truth-results-<timestamp>.json`. Successful answers are marked
for semantic review; GitHub MCP, jailbreak guardrails, and RBAC cases are
reported as unsupported because those integrations are not implemented in this
PoC. A rate-limit or server-error response stops the run to avoid consuming more
requests; inspect the app log for the provider's exact status and message before
deciding whether to try a different chat model.

The chat model defaults to `gemini-3.8-flash`. If logs confirm that model's quota/rate limit and the Google AI Studio project
has capacity on another supported model, set `GEMINI_CHAT_MODEL` before
restarting. Confirm the current model ID in Google AI Studio/API model list; do
not assume that a model ID is enabled for every account. For example, try
`gemini-3.6-flash` only if it is listed as available to your project:

```powershell
$env:GEMINI_CHAT_MODEL = "gemini-3.6-flash"
```

Set this in the same PowerShell window used to launch Spring Boot, then restart
the app. This changes only the chat model; the embedding model remains
`gemini-embedding-001`. To switch back to `gemini-3.8-flash`, run
`Remove-Item Env:GEMINI_CHAT_MODEL -ErrorAction SilentlyContinue` in that
window before restarting; alternatively change the `GEMINI_CHAT_MODEL` value in
`src/main/resources/application.properties`.

Keep the key out of source control. Google AI Studio API access is subject to
Google's free-tier availability, quotas, and terms; do not enable billing if you
want to avoid paid usage. If both `GOOGLE_API_KEY` and `GEMINI_API_KEY` are set,
the Google GenAI SDK uses `GOOGLE_API_KEY`.

Chat provider and vector-store failures are logged by the application as
`Chat request failed`, including the exception and its cause. Temporary
provider errors and rate limits return HTTP 503; other upstream errors return
HTTP 502. Check the Spring Boot console for the underlying cause before
retrying, especially if Gemini API quota or availability may be limited.

### Reference Documentation
For further reference, please consider the following sections:

* [Official Apache Maven documentation](https://maven.apache.org/guides/index.html)
* [Spring Boot Maven Plugin Reference Guide](https://docs.spring.io/spring-boot/4.1.1/maven-plugin)
* [Create an OCI image](https://docs.spring.io/spring-boot/4.1.1/maven-plugin/build-image.html)

### Maven Parent overrides

Due to Maven's design, elements are inherited from the parent POM to the project POM.
While most of the inheritance is fine, it also inherits unwanted elements like `<license>` and `<developers>` from the parent.
To prevent this, the project POM contains empty overrides for these elements.
If you manually switch to a different parent and actually want the inheritance, you need to remove those overrides.
