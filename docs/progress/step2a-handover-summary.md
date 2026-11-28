# Step 2a：交接摘要 API（後端）

## 這次做了什麼
- `GET /api/v1/handover/summary`：給作者與日期區間，回傳任務狀態分布與依優先度（或進度）排序的待辦清單。所有數字由 SQL / 程式計算，不經過 LLM。
- **任務去重**：同一作者、名稱相似的任務（去除空白標點後，字元二連詞 Jaccard ≥ 0.5）視為同一件事，保留最新日報的狀態，並記錄出現次數。例如前一天的「明日計畫（0%）」隔天變「已完成（100%）」會合併成一筆 DONE。
- **狀態分類**：DONE（100%）、IN_PROGRESS（1–99%）、BLOCKED（卡關且未完成）、NOT_STARTED（0%）。
- **權限**：員工只能查自己，且只看 DEVELOPER / PUBLIC 等級日報；主管可查任何人，且可看 MANAGER 等級日報。與 Qdrant 的篩選規則一致。
- **作者以登入帳號為準**：新增 `user_authors` 對應表（Firebase email → 作者姓名，範例見 `Data/user_authors.json`）。聊天視窗新增日報時，作者不再由 Gemini 從文字猜，避免用 A 的帳號輸入「我是 B…」記在 B 名下。
- `SecurityConfig` 開放 `/api/v1/handover/**`（DEVELOPER / MANAGER）並允許 GET。

## 帳號對應（請依實際測試帳號調整）
| 登入帳號 | 對應作者 |
|---|---|
| dev_test@example.com | 林小華 |
| mgr_test@example.com | 黃雅婷 |

## 測試
`.\mvnw.cmd clean test`（新增約 20 個測試）。

## 實際畫面
2a 只有後端 API，畫面驗證在 2b（前端圖表）進行。
