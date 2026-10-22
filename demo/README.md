# Daily Report RAG AI Chatbot POC

本專案為基於 SDLC（軟體開發生命週期）脈絡開發之「工作日報 AI 檢索問答系統」，結合 Spring Boot 4、React + Vite、Qdrant 向量資料庫與 Google Gemini API，實現 RAG (Retrieval-Augmented Generation) 語意問答。

## 🛠️ 環境需求 (Prerequisites)

- **Java**: 17 +
- **Maven**: 3.8+ (專案內建 `mvnw` wrapper)
- **Docker / Docker Desktop**: 用於執行 Qdrant Vector Database
- **Node.js / npm**: 執行 React + Vite 前端
- **PowerShell**: Windows PowerShell 5.1 / PowerShell 7+
- **Google Gemini API Key**: 支援 Gemini API 之金鑰

---
## ✨ 核心架構亮點 (Key Features)

1. **雙 Collection 隔離架構 (Dual-Collection Search)**：
   - 將 **權威知識庫文件 (`department_knowledge_docs`)** 與 **同仁工作日報 (`department_daily_reports`)** 獨立儲存。
   - **衝突優先調和**：環境與部署類問題僅檢索權威文件；若日報與文件資訊衝突（如過期 IP），系統嚴格以權威文件為準。
2. **LLM 多標籤意圖分類器 (Multi-label Intent Classification)**：
   - 問題可路由至 `ENVIRONMENT_DOC`（環境文件）、`DAILY_STATUS`（日報進度）或 `GITHUB_SDLC`（GitHub Commit、PR、Issue 與討論紀錄）；內部資料類別也支援多標籤。
3. **GitHub MCP Client**：
   - Spring Boot 透過 `ProcessBuilder` 啟動 GitHub MCP Server，使用 stdio JSON-RPC 初始化並列出/呼叫 MCP Tools。
   - PAT 只透過子程序環境變數傳入；可定期透過 MCP 同步 Commit、PR、Issue 與討論留言至 `github_sdlc_events` collection，供聊天檢索。
4. **自動化評測體系 (Automated Benchmark)**：
   - 提供 PowerShell 自動化腳本，針對 Ground Truth 測試集與意圖分類準確率進行量化評測。

---

## 🚀 快速啟動與測試步驟 (Execution Guide)

步驟 1：啟動 Qdrant 向量資料庫 (Docker)
開啟終端機執行以下指令啟動 Qdrant 容器：
```powershell
docker run -d --name qdrant-dev-v1-18 -p 6333:6333 -p 6334:6334 qdrant/qdrant:v1.18.0
```

步驟 2：設定環境變數並啟動 Spring Boot 後端
在 `demo` 目錄開啟 PowerShell 視窗，設定 API Key 並啟動服務：
【首次啟動】（自動匯入權威文件與 71 筆日報）
```powershell
$env:GOOGLE_API_KEY="你的_GEMINI_API_KEY"
$env:GITHUB_PERSONAL_ACCESS_TOKEN="你的_GITHUB_PAT"
$env:GITHUB_REPOSITORY_OWNER="你的_GITHUB_組織或帳號"
$env:GITHUB_REPOSITORY_NAME="你的_REPOSITORY"
.\mvnw.cmd spring-boot:run
```

【後續啟動】（跳過向量重複寫入，加速啟動速度）
```powershell
$env:GOOGLE_API_KEY="你的_GEMINI_API_KEY"
$env:GITHUB_PERSONAL_ACCESS_TOKEN="你的_GITHUB_PAT"
$env:GITHUB_REPOSITORY_OWNER="你的_GITHUB_組織或帳號"
$env:GITHUB_REPOSITORY_NAME="你的_REPOSITORY"
$env:APP_INGESTION_ENABLED="false"
$env:GEMINI_CHAT_MODEL="gemini-3.5-flash-lite"
.\mvnw.cmd spring-boot:run
```

預設聊天模型為 `gemini-3.5-flash-lite`。若需切換模型，可透過 `GEMINI_CHAT_MODEL` 環境變數指定其他支援的 Gemini 文字生成模型。

伺服器啟動成功後，預設監聽於 http://localhost:8080。

Linux / macOS 啟動方式：
```bash
cd demo
export GOOGLE_API_KEY="你的_GEMINI_API_KEY"
./mvnw spring-boot:run
```

步驟 3：啟動 React 聊天前端並測試問答
在另一個終端機視窗，從 repository 根目錄執行：
```bash
cd my-ai-ui
npm install
npm run dev
```

Windows PowerShell 可從 `demo` 目錄執行：
```powershell
cd ..\my-ai-ui
npm install
npm run dev
```

Vite 預設監聽 5173 埠，請開啟終端機顯示的前端網址（通常是 http://localhost:5173），輸入問題並按「送出」。前端會以 `POST` 呼叫 `http://localhost:8080/api/v1/chat/query`；Spring Boot 的 `ChatController` 已允許 `http://localhost:5173` 跨域存取。若瀏覽器使用不同的前端來源網址（例如 VS Code Port Forwarding 網址），請將該完整來源加入 `ChatController.java` 的 `@CrossOrigin`，不要使用 `*` 開放所有來源。

聊天請求的 `filters` 為選填；一般問答不需要傳入，省略時會依問題意圖檢索相應資料。可用以下命令獨立驗證後端 API：
```bash
curl -X POST http://localhost:8080/api/v1/chat/query \
  -H 'Origin: http://localhost:5173' \
  -H 'Content-Type: application/json' \
  -d '{"message":"測試機的 IP 是多少？","role":"DEVELOPER"}'
```

成功時會取得包含 `answer`、`intent`、`intents` 欄位的 JSON 回應。`http://localhost:8080/` 並非前端頁面或聊天端點；請從 Vite 網址開啟 UI，並以 `POST /api/v1/chat/query` 呼叫聊天 API。

常見連線問題：
- 瀏覽器無法開啟前端：確認 `npm run dev` 正在執行，使用終端機顯示的網址，並確認 VS Code 已轉發 5173 埠。
- CORS 錯誤：確認前端頁面的來源與 `@CrossOrigin` 設定一致；若使用不同來源（含協定、網域或埠），需更新允許來源。
- API 回應服務錯誤：確認 Spring Boot 正在 8080 埠執行、Qdrant 正在 6334 埠執行，且已設定有效的 `GOOGLE_API_KEY`。

步驟 4：執行 Ground Truth 基準測試 (Benchmark Test)
開啟第二個 PowerShell 視窗，執行自動化評測腳本：
【單題驗證 (TC-001)】
```powershell
& "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File ".\scripts\evaluate-ground-truth.ps1" -TestIds TC-001
```

【完整 Ground Truth 測試集】
```powershell
& "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File ".\scripts\evaluate-ground-truth.ps1"
```

評測結果將自動儲存於 target/ground-truth-results-*.json。

步驟 5：執行意圖分類準確率測試
確認後端服務已啟動後，在 PowerShell 執行：
```powershell
& "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File ".\scripts\evaluate-intent-accuracy.ps1"
```

測試集位於 `src/main/resources/Data/intent_testset.json`，包含 `ENVIRONMENT_DOC`、`DAILY_STATUS`、`GITHUB_SDLC` 與 `GENERAL_CHAT` 類別，並支援單標籤與多標籤案例。`DAILY_STATUS` 僅適用於查詢已記錄的歷史日報；一般工作建議屬於 `GENERAL_CHAT`。結果與整體準確率會儲存於 `target/intent-accuracy-*.json`。聊天 API 以 `intent` 回傳主要分類，並以 `intents` 回傳完整標籤。
評測腳本可用 `-TestIds INT-001,INT-007` 挑選題目、用 `-ModelLabel gemini-3.1-flash-lite` 註記模型；預設每題間隔 20 秒以降低 RPM 壓力。切換模型時須先以新的 `GEMINI_CHAT_MODEL` 值重啟後端。

## GitHub MCP 設定

- Node.js 與可執行的 `npx` 必須安裝在啟動 Spring Boot 的環境中。預設以 `npx -y @modelcontextprotocol/server-github` 啟動 MCP Server。
- 設定 `GITHUB_PERSONAL_ACCESS_TOKEN`、`GITHUB_REPOSITORY_OWNER` 及 `GITHUB_REPOSITORY_NAME`。PAT 權限需符合目標 repository 的讀取需求，請勿將 token 寫入程式碼或提交至版本控制。
- `GITHUB_MCP_COMMAND` 與 `GITHUB_MCP_PACKAGE` 可分別覆寫啟動命令及 MCP 套件名稱。
- 啟用自動同步：設定 `GITHUB_SYNC_ENABLED=true`。預設在啟動 15 秒後首次同步，之後每 15 分鐘同步一次；可用 `GITHUB_SYNC_INTERVAL_MS`（毫秒）調整頻率。同步需要 MCP 提供 `list_commits`、`list_pull_requests`、`list_issues` 與 PR 留言工具。若 MCP 沒有 Issue 留言工具，系統會以同一個 PAT 呼叫 GitHub REST API 讀取 Issue 留言；API 失敗會讓該輪同步失敗並記錄錯誤，不會靜默略過。
- Commit、PR、Issue 及留言會以 repository 與 GitHub 原始 ID 產生穩定文件 ID；重複排程會更新同一筆 Qdrant point，而不新增重複文件。同步成功日誌會分別列出 Commit、PR、PR 留言、Issue、Issue 留言及總筆數，方便確認各類事件是否實際同步；失敗會記錄錯誤並在下個排程重試。
- `GITHUB_SYNC_PAGE_SIZE` 可設定每種清單的每頁筆數，範圍為 1–100，預設 100。每次處理各清單的第一頁，PR/Issue 清單依最近更新排序；留言則依 MCP 工具回傳的頁面處理。
- `QDRANT_GITHUB_EVENTS_COLLECTION` 可變更 GitHub 事件 collection 名稱，預設 `github_sdlc_events`。
- 每筆事件帶有可篩選的 `event_type`、`event_date`、`repository`、`author`、`state`、`labels`、`number` 元數據。`references` 保存關聯類型、目標類型/ID 與 URL；另提供 `reference_types`、`reference_ids`、`reference_urls` 扁平欄位供 Qdrant payload 過濾。PR 會連結 head commit，使用 `Closes/Fixes/Resolves #N` 的 PR 描述會建立 issue-pr 關聯；留言連回所屬 PR/Issue，文字中的 `#N` 會保留 mention 關聯。來源 URL 與 references 也附加在文件文字中，讓聊天回答能提供可點擊的 GitHub 連結。
- 聊天 API 可透過選填 `filters` 對 GitHub 語意檢索套用 payload filter（欄位可選 `eventType`、`repository`、`author`、`state`、`label`、`number`、`referenceType`、`referenceId`）。提供 `filters` 時只查詢 GitHub 事件，不會用日報或知識文件補答案；若沒有符合條件的事件，會直接回覆查無符合條件的 GitHub 事件。不提供 filters 時維持原有三庫檢索。例如：
  ```bash
  curl -X POST http://localhost:8080/api/v1/chat/query \
    -H 'Content-Type: application/json' \
    -d '{"message":"哪個 PR 修復登入問題？","role":"DEVELOPER","filters":{"eventType":"pull_request","state":"merged","label":"bug"}}'
  ```
- `GITHUB_SYNC_ENABLED` 預設為 `false`，因此原有聊天與啟動流程不會在未設定 GitHub PAT 時嘗試同步。要查詢最新同步資料，先啟用同步並等待成功日誌。
- `GITHUB_SDLC` 問題會同時檢索權威知識文件、日報與 GitHub 事件；對目前環境配置與 SOP 有衝突時，權威知識文件優先。提交、PR、Issue 與討論事實則以 GitHub 事件為依據。

Linux/macOS 啟動範例：
```bash
export GOOGLE_API_KEY="你的_GEMINI_API_KEY"
export GITHUB_PERSONAL_ACCESS_TOKEN="你的_GITHUB_PAT"
export GITHUB_REPOSITORY_OWNER="你的_GITHUB_組織或帳號"
export GITHUB_REPOSITORY_NAME="你的_REPOSITORY"
export GITHUB_SYNC_ENABLED=true
./mvnw spring-boot:run
```

PowerShell 請以 `$env:GITHUB_SYNC_ENABLED="true"` 啟用排程，並在啟動 `.\mvnw.cmd spring-boot:run` 前設定好上述必要環境變數。

## 文件與日報檢索優先級

- 權威專案文件由 `src/main/resources/Data/knowledge_docs.json` 載入 `department_knowledge_docs_gemini_embedding_001` collection。
- 同仁日報由 `src/main/resources/Data/mock_daily_reports.json` 載入 `department_daily_reports_gemini_embedding_001` collection。
- 單一 `ENVIRONMENT_DOC` 問題只檢索權威文件；單一 `DAILY_STATUS` 問題只檢索日報；同時涉及兩者時會同時檢索；`GENERAL_CHAT` 不執行向量檢索。
- 日報可能記錄測試機 IP `192.168.1.100`，權威文件記載的現行 IP 為 `192.168.1.105`。若問題涉及現行環境配置，系統以權威文件為準；日報僅代表歷史工作紀錄。Ground Truth 的 TC-003 用來檢查回答是否採用權威文件。
- 可用 `QDRANT_KNOWLEDGE_DOCS_COLLECTION` 與 `QDRANT_DAILY_REPORTS_COLLECTION` 分別設定 collection 名稱。日報 collection 預設沿用既有 `QDRANT_COLLECTION` 設定。

---

📊 基準測試評測成果 (Benchmark Results)
基於 20 題 Ground Truth 測試集（17 題支援案例 + 3 題進階擴充跳過案例）：

API 連線成功率：100%（17/17 題成功回傳，0 次 429/5xx 錯誤）

嚴格語意完全符合：82.4% (14/17 題)

折半計分合格率：85.3% (14.5/17 題)

平均 API 響應時間：1.1 秒 ~ 1.7 秒

意圖分類準確率 (Intent Accuracy)：100% (13/13 題精準路由)

---