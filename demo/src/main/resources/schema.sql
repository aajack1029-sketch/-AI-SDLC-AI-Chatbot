-- 日報結構化統計表（語意內容仍存放於 Qdrant；這裡只放可做 SQL 統計的欄位）
CREATE TABLE IF NOT EXISTS daily_reports (
    report_id   VARCHAR(100)  PRIMARY KEY,
    report_date DATE          NOT NULL,
    author      VARCHAR(100)  NOT NULL,
    role_title  VARCHAR(100),
    project     VARCHAR(200),
    role_access VARCHAR(20)   NOT NULL
);

CREATE TABLE IF NOT EXISTS report_tasks (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_id        VARCHAR(100)  NOT NULL,
    task_name        VARCHAR(500)  NOT NULL,
    progress_percent INT           NOT NULL,
    priority         VARCHAR(10)   NOT NULL,
    is_blocked       BOOLEAN       NOT NULL DEFAULT FALSE,
    blocker_note     VARCHAR(1000),
    -- progress_percent (0-100) 與 priority (HIGH/MEDIUM/LOW) 由 Java 端
    -- StructuredTask / TaskPriority 驗證；H2 的 CHECK 限制在此環境執行時出錯 (23514)，暫不使用。
    CONSTRAINT fk_report_tasks_report FOREIGN KEY (report_id)
        REFERENCES daily_reports (report_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_daily_reports_author_date ON daily_reports (author, report_date);
CREATE INDEX IF NOT EXISTS idx_report_tasks_report ON report_tasks (report_id);
