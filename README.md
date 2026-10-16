# SDLC AI Chatbot

以 React、Spring Boot、Google Gemini 與 Qdrant 建置的 AI 問答系統。前端提供聊天介面，後端依問題意圖檢索權威知識文件、工作日報或 GitHub SDLC 事件，再回傳答案與意圖標籤。

## 專案結構

- `my-ai-ui/`：React、TypeScript、Vite 前端
- `demo/`：Spring Boot 後端與聊天 API
- [後端詳細文件](./demo/README.md)：RAG、Qdrant 匯入、GitHub MCP 與評測設定

## 環境需求

- Node.js 與 npm
- Java 17+
- Qdrant（預設 gRPC `localhost:6334`；HTTP 管理介面預設 `localhost:6333`）
- Google Gemini API Key

如需使用 GitHub SDLC 事件同步，另需 Node.js／`npx`、GitHub Personal Access Token，以及目標 repository 的 owner/name。

## 啟動完整流程

前端與後端需在不同終端機執行。第一次使用時，先啟動 Qdrant：

```bash
docker run -d --name qdrant-dev -p 6333:6333 -p 6334:6334 qdrant/qdrant:v1.18.0
```

如果已有名為 `qdrant-dev` 的容器，請改為啟動既有容器：

```bash
docker start qdrant-dev
```

### 1. 設定並啟動後端

在終端機 A：

```bash
cd demo
export GOOGLE_API_KEY="你的_GEMINI_API_KEY"
./mvnw spring-boot:run
```

Windows PowerShell 可使用：

```powershell
cd demo
$env:GOOGLE_API_KEY = "你的_GEMINI_API_KEY"
.\mvnw.cmd spring-boot:run
```

後端預設監聽 `http://localhost:8080`。初次啟動會依設定匯入知識文件與日報；啟動時需能連線至 Gemini API 與 Qdrant。

GitHub MCP 整合為選用功能。若要啟用，請另外設定 `GITHUB_PERSONAL_ACCESS_TOKEN`、`GITHUB_REPOSITORY_OWNER`、`GITHUB_REPOSITORY_NAME`；自動同步則需設定 `GITHUB_SYNC_ENABLED=true`。請勿將 API key 或 token 寫入程式碼或提交至 Git。

### 2. 啟動前端

在終端機 B：

```bash
cd my-ai-ui
npm install
npm run dev
```

開啟 Vite 顯示的網址，預設為 <http://localhost:5173/>。`dev` 指令會使用 `--host 0.0.0.0`，以支援容器或遠端開發環境的連接埠轉發；在 VS Code Dev Containers 中，若本機瀏覽器無法連線，請確認 Ports 面板已轉發 `5173`。

聊天介面會將問題以 `POST` 傳送至 `http://localhost:8080/api/v1/chat/query`。目前前端不會附帶 GitHub `filters`，因此使用一般的多來源檢索流程。

### 3. 確認 API

聊天 API 僅接受 `POST`。例如：

```bash
curl -X POST http://localhost:8080/api/v1/chat/query \
  -H 'Content-Type: application/json' \
  -d '{"message":"測試機 IP 是多少？","role":"DEVELOPER"}'
```

成功時會回傳 `answer`、主要意圖 `intent` 與完整意圖清單 `intents`。不要以瀏覽器直接開啟 `/api/v1/chat/query` 測試，因為瀏覽器網址列會送出 `GET`；也不要期待後端根網址 `http://localhost:8080/` 顯示 React 頁面，前端和後端是分開啟動的服務。

後端目前只允許 `http://localhost:5173` 跨來源存取。若前端改用其他 origin（包含不同 port），請同步更新 `ChatController` 的 `@CrossOrigin` 設定。

## 驗證與測試

前端 TypeScript 檢查與 production build：

```bash
cd my-ai-ui
npm run build
npm run lint
```

後端測試：

```bash
cd demo
./mvnw test
```

## 疑難排解

- **前端頁面空白或一直載入**：確認開啟的是 `http://localhost:5173/`，Vite 終端機仍在執行，且遠端／容器環境已轉發 port `5173`。
- **聊天送出後連線錯誤**：確認後端在 port `8080` 執行、`GOOGLE_API_KEY` 有效、Qdrant 可連線，並確認前端 origin 與 CORS 設定一致。
- **瀏覽器 Console 沒有訊息**：不代表 React 沒載入；請在 DevTools 的 Network 檢查文件、`main.tsx`、`App.tsx` 是否成功載入。
- **回答缺少資料或呼叫失敗**：檢查後端日誌、Gemini API 配額、Qdrant 連線及初次資料匯入狀態。

## RAG 檢索與回答行為

- 系統使用獨立的 Qdrant collections 儲存權威知識文件、同仁工作日報及 GitHub SDLC 事件。
- 問題由意圖分類器路由至 `ENVIRONMENT_DOC`（環境文件）、`DAILY_STATUS`（歷史日報）、`GITHUB_SDLC`（GitHub 事件）或 `GENERAL_CHAT`。同一問題也可能同時命中多個資料類別；API 會以 `intent` 回傳主要意圖，並以 `intents` 回傳完整標籤。
- 單一 `ENVIRONMENT_DOC` 問題檢索權威文件；單一 `DAILY_STATUS` 問題檢索日報；同時涉及兩者時會同時檢索；`GENERAL_CHAT` 不執行向量檢索。
- 若日報與權威文件資訊衝突，現行環境設定與 SOP 以權威文件為準；日報只代表歷史工作紀錄。例如日報中的測試機 IP 可能是 `192.168.1.100`，而權威文件記載的現行 IP 是 `192.168.1.105`。
- `GITHUB_SDLC` 問題會檢索權威文件、日報及 GitHub 事件。環境設定與 SOP 以權威文件為準；Commit、PR、Issue 與討論紀錄則以 GitHub 事件為依據。
- 後端首次啟動時會依 `APP_INGESTION_ENABLED` 匯入資料。權威文件與日報來源分別為 `demo/src/main/resources/Data/knowledge_docs.json` 與 `demo/src/main/resources/Data/mock_daily_reports.json`。
- 預設 collections 為 `department_knowledge_docs_gemini_embedding_001`、`department_daily_reports_gemini_embedding_001` 與 `github_sdlc_events`。可分別使用 `QDRANT_KNOWLEDGE_DOCS_COLLECTION`、`QDRANT_DAILY_REPORTS_COLLECTION`、`QDRANT_GITHUB_EVENTS_COLLECTION` 覆寫；日報 collection 也支援舊有的 `QDRANT_COLLECTION` 設定。
- Qdrant 連線預設使用 `QDRANT_HOST=localhost`、`QDRANT_PORT=6334`；首次建立 collection 的設定由 `QDRANT_INIT_SCHEMA` 控制，預設為 `true`。

## GitHub MCP 整合（選用）

Node.js 與 `npx` 必須安裝在啟動 Spring Boot 的環境中。預設會以 `npx -y @modelcontextprotocol/server-github` 啟動 GitHub MCP Server。設定以下環境變數以連線至目標 repository：

```bash
export GITHUB_PERSONAL_ACCESS_TOKEN="你的_GITHUB_PAT"
export GITHUB_REPOSITORY_OWNER="你的_GITHUB_組織或帳號"
export GITHUB_REPOSITORY_NAME="你的_REPOSITORY"
```

PAT 權限需符合目標 repository 的讀取需求；請勿將 token 寫入程式碼或提交至版本控制。MCP 啟動命令及套件可分別以 `GITHUB_MCP_COMMAND`、`GITHUB_MCP_PACKAGE` 覆寫。

GitHub 自動同步預設關閉。設定 `GITHUB_SYNC_ENABLED=true` 後，服務會在啟動 15 秒後進行首次同步，之後預設每 15 分鐘同步一次；可用 `GITHUB_SYNC_INTERVAL_MS`（毫秒）調整頻率。同步需要 MCP 提供 Commit、PR、Issue 與 PR 留言工具；若沒有 Issue 留言工具，系統會以 GitHub REST API 讀取 Issue 留言。同步失敗會記錄錯誤並在下一輪排程重試。`GITHUB_SYNC_PAGE_SIZE` 可設定每種清單每頁筆數（1–100，預設 100），`QDRANT_GITHUB_EVENTS_COLLECTION` 可設定事件 collection（預設 `github_sdlc_events`）。

Linux / macOS 啟用同步的範例（在 `demo` 目錄執行）：

```bash
export GOOGLE_API_KEY="你的_GEMINI_API_KEY"
export GITHUB_PERSONAL_ACCESS_TOKEN="你的_GITHUB_PAT"
export GITHUB_REPOSITORY_OWNER="你的_GITHUB_組織或帳號"
export GITHUB_REPOSITORY_NAME="你的_REPOSITORY"
export GITHUB_SYNC_ENABLED=true
./mvnw spring-boot:run
```

PowerShell 請在啟動 `.\mvnw.cmd spring-boot:run` 前設定相同變數，例如 `$env:GITHUB_SYNC_ENABLED="true"`。

GitHub 事件包含 `event_type`、`event_date`、`repository`、`author`、`state`、`labels`、`number` 等可篩選 metadata，也保留事件間的 references 與來源 URL。聊天 API 可選擇傳入 `filters`，套用 GitHub payload 篩選；一旦提供 filters，該次請求只查詢 GitHub 事件，不會以日報或知識文件補答案。不提供 filters 時，維持依意圖進行多來源檢索。可用欄位為 `eventType`、`repository`、`author`、`state`、`label`、`number`、`referenceType`、`referenceId`。例如：

```bash
curl -X POST http://localhost:8080/api/v1/chat/query \
  -H 'Content-Type: application/json' \
  -d '{"message":"哪個 PR 修復登入問題？","role":"DEVELOPER","filters":{"eventType":"pull_request","state":"merged","label":"bug"}}'
```

## 基準評測

Ground Truth 與意圖分類評測腳本位於 `demo/scripts/`，測試資料分別位於 `demo/src/main/resources/Data/ground_truth_testset.json` 與 `demo/src/main/resources/Data/intent_testset.json`。下列 PowerShell 命令請在 `demo` 目錄執行；測試前須先啟動後端：

```powershell
.\scripts\evaluate-ground-truth.ps1 -TestIds TC-001
.\scripts\evaluate-ground-truth.ps1
.\scripts\evaluate-intent-accuracy.ps1
```

評測結果會儲存在 `demo/target/`。意圖評測可用 `-TestIds INT-001,INT-007` 選擇題目，並以 `-ModelLabel gemini-3.1-flash-lite` 標註模型；預設每題間隔 20 秒以降低 API RPM 壓力。若要切換聊天模型，請先以新的 `GEMINI_CHAT_MODEL` 值重啟後端。

專案 README 曾記錄一組基準結果：Ground Truth API 連線成功率 100%（17/17）、嚴格語意符合率 82.4%（14/17）、折半計分合格率 85.3%（14.5/17）、平均 API 回應時間 1.1–1.7 秒，以及意圖分類準確率 100%（13/13）。實際結果會隨模型、資料與 API 狀態變動，請以重新執行評測的結果為準。
