# Step 1a：結構化 SQL 資料庫（H2）

## 這次做了什麼
- 新增 SQL 資料表 `daily_reports`、`report_tasks`（`schema.sql`），欄位含 `progress_percent`、`priority`、`is_blocked`、`blocker_note`。
- 新增 `DailyReportSqlRepository`：寫入（同一份日報重複匯入不會產生重複任務）與統計查詢（依作者計算任務數、平均進度、卡關數）。
- 新增 4 個單元測試（不需要 Gemini / Qdrant）。
- 資料庫預設為本機 H2 檔案 `demo/data/daily_reports`（已加入 `.gitignore`）。

## 測試結果
```powershell
cd demo
.\mvnw.cmd clean test
```
截圖：`img/step1a-test-success.png`（預期 `BUILD SUCCESS`，測試數 72）

## 後續
- Step 1b：用 Gemini 對 72 筆範例日報抽取進度/優先度/卡關，存成 JSON，匯入此資料庫。
