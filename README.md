# Daily Report RAG AI Chatbot POC

本專案為基於 SDLC（軟體開發生命週期）脈絡開發之「工作日報 AI 檢索問答系統」，結合 Spring Boot 3、Qdrant 向量資料庫與 Google Gemini API，實現高精準度之 RAG (Retrieval-Augmented Generation) 語意問答。

## 🛠️ 環境需求 (Prerequisites)

- **Java**: 17 +
- **Maven**: 3.8+ (專案內建 `mvnw` wrapper)
- **Docker / Docker Desktop**: 用於執行 Qdrant Vector Database
- **PowerShell**: Windows PowerShell 5.1 / PowerShell 7+
- **Google Gemini API Key**: 支援 Gemini API 之金鑰

---

## 🚀 快速啟動與測試步驟 (Execution Guide)

步驟1:啟動 Qdrant 向量資料庫 (Docker)
開啟終端機執行以下指令啟動 Qdrant 容器：
```powershell
docker run -d --name qdrant-dev-v1-18 -p 6333:6333 -p 6334:6334 qdrant/qdrant:v1.18.0

步驟 2：設定環境變數並啟動 Spring Boot 後端
在專案根目錄開啟 PowerShell 視窗，設定 API Key 並啟動服務：
【首次啟動】（自動將 70 筆日報寫入向量庫）
```powershell
$env:GOOGLE_API_KEY="你的_GEMINI_API_KEY"
.\mvnw.cmd spring-boot:run

【後續啟動】（跳過向量重複寫入，加速啟動速度）
```powershell
$env:GOOGLE_API_KEY="你的_GEMINI_API_KEY"
$env:APP_INGESTION_ENABLED="false"
$env:GEMINI_CHAT_MODEL="gemini-3.5-flash-lite"  
.\mvnw.cmd spring-boot:run

->$env:GEMINI_CHAT_MODEL：這個可以看看自己的RPM來去更改，如果已經達到流量限制，只要是Category:Text-out models的，都可以使用。

伺服器啟動成功後，預設監聽於 http://localhost:8080。

步驟 3：執行 Ground Truth 基準測試 (Benchmark Test)
開啟第二個 PowerShell 視窗，執行自動化評測腳本：
【單題驗證 (TC-001)】
```powershell
& "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File ".\scripts\evaluate-ground-truth.ps1" -TestIds TC-001

【完整 Ground Truth 測試集】
```powershell
& "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File ".\scripts\evaluate-ground-truth.ps1"

評測結果將自動儲存於 target/ground-truth-results-*.json。

📊 基準測試評測成果 (Benchmark Results)
基於 20 題 Ground Truth 測試集（17 題支援案例 + 3 題進階擴充跳過案例）：

API 連線成功率：100%（17/17 題成功回傳，0 次 429/5xx 錯誤）

嚴格語意完全符合：82.4% (14/17 題)

折半計分合格率：85.3% (14.5/17 題)

平均 API 響應時間：1.1 秒 ~ 1.7 秒