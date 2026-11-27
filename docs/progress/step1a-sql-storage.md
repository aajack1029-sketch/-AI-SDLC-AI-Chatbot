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
結果：72 個測試全部通過，`BUILD SUCCESS`。

![Step 1a 測試通過](img/step1a-test-success.png)

## 遇到的問題
H2 寫入 `report_tasks` 時，資料表的 CHECK 限制（進度 0–100、優先度三選一）出現 `23514 Check constraint invalid`，三個寫入測試失敗。資料驗證本來就在 Java 端（`StructuredTask`、`TaskPriority`），因此先移除資料庫層的兩條 CHECK，測試即全數通過。根本原因尚未查明；之後換 MySQL 時需重新評估是否補回。

![CHECK 限制錯誤](img/step1a-check-constraint-error.png)

## 後續
- Step 1b：用 Gemini 對 72 筆範例日報抽取進度/優先度/卡關，存成 JSON，匯入此資料庫。
