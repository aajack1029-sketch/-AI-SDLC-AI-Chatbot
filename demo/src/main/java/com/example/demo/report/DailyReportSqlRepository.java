package com.example.demo.report;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 日報結構化資料的 SQL 存取層。
 * 數字統計（平均進度、卡關數）交給 SQL 計算，避免讓 LLM 估算造成幻覺。
 */
@Repository
public class DailyReportSqlRepository {

    private final JdbcClient jdbc;

    public DailyReportSqlRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** 以 reportId 為鍵覆寫：重複匯入同一份日報不會產生重複任務。 */
    @Transactional
    public void save(StructuredReport report) {
        jdbc.sql("DELETE FROM report_tasks WHERE report_id = :id")
                .param("id", report.reportId()).update();
        jdbc.sql("DELETE FROM daily_reports WHERE report_id = :id")
                .param("id", report.reportId()).update();

        jdbc.sql("""
                INSERT INTO daily_reports (report_id, report_date, author, role_title, project, role_access)
                VALUES (:id, :date, :author, :roleTitle, :project, :roleAccess)
                """)
                .param("id", report.reportId())
                .param("date", Date.valueOf(report.date()))
                .param("author", report.author())
                .param("roleTitle", report.roleTitle())
                .param("project", report.project())
                .param("roleAccess", report.roleAccess())
                .update();

        for (StructuredTask task : report.tasks()) {
            jdbc.sql("""
                    INSERT INTO report_tasks
                        (report_id, task_name, progress_percent, priority, is_blocked, blocker_note)
                    VALUES (:id, :name, :progress, :priority, :blocked, :note)
                    """)
                    .param("id", report.reportId())
                    .param("name", task.taskName())
                    .param("progress", task.progressPercent())
                    .param("priority", task.priority().name())
                    .param("blocked", task.blocked())
                    .param("note", task.blockerNote())
                    .update();
        }
    }

    public long countReports() {
        return jdbc.sql("SELECT COUNT(*) FROM daily_reports").query(Long.class).single();
    }

    public long countTasks() {
        return jdbc.sql("SELECT COUNT(*) FROM report_tasks").query(Long.class).single();
    }

    public List<StructuredTask> findTasksByReportId(String reportId) {
        return jdbc.sql("""
                SELECT task_name, progress_percent, priority, is_blocked, blocker_note
                FROM report_tasks WHERE report_id = :id ORDER BY id
                """)
                .param("id", reportId)
                .query((rs, rowNum) -> new StructuredTask(
                        rs.getString("task_name"),
                        rs.getInt("progress_percent"),
                        TaskPriority.valueOf(rs.getString("priority")),
                        rs.getBoolean("is_blocked"),
                        rs.getString("blocker_note")))
                .list();
    }

    /** 指定日期區間內，每位作者的任務數、平均進度與卡關任務數。 */
    public List<AuthorProgress> summarizeByAuthor(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT r.author AS author,
                       COUNT(t.id) AS task_count,
                       AVG(t.progress_percent * 1.0) AS avg_progress,
                       SUM(CASE WHEN t.is_blocked THEN 1 ELSE 0 END) AS blocked_count
                FROM daily_reports r
                JOIN report_tasks t ON t.report_id = r.report_id
                WHERE r.report_date BETWEEN :from AND :to
                GROUP BY r.author
                ORDER BY r.author
                """)
                .param("from", Date.valueOf(from))
                .param("to", Date.valueOf(to))
                .query((rs, rowNum) -> new AuthorProgress(
                        rs.getString("author"),
                        rs.getLong("task_count"),
                        rs.getDouble("avg_progress"),
                        rs.getLong("blocked_count")))
                .list();
    }

    /** 交接摘要用：單一任務與它所屬日報的日期、編號。 */
    public record TaskRow(
            String reportId, LocalDate reportDate, String taskName, int progressPercent,
            TaskPriority priority, boolean blocked, String blockerNote) {
    }

    /** 作者在日期區間內、且呼叫者有權限看的任務 (依日報日期由舊到新)。 */
    public List<TaskRow> findTaskRows(String author, LocalDate from, LocalDate to, List<String> accessibleRoles) {
        return jdbc.sql("""
                SELECT r.report_id, r.report_date, t.task_name, t.progress_percent,
                       t.priority, t.is_blocked, t.blocker_note
                FROM daily_reports r
                JOIN report_tasks t ON t.report_id = r.report_id
                WHERE r.author = :author
                  AND r.report_date BETWEEN :from AND :to
                  AND r.role_access IN (:roles)
                ORDER BY r.report_date, r.report_id, t.id
                """)
                .param("author", author)
                .param("from", Date.valueOf(from))
                .param("to", Date.valueOf(to))
                .param("roles", accessibleRoles)
                .query((rs, rowNum) -> new TaskRow(
                        rs.getString("report_id"),
                        rs.getDate("report_date").toLocalDate(),
                        rs.getString("task_name"),
                        rs.getInt("progress_percent"),
                        TaskPriority.valueOf(rs.getString("priority")),
                        rs.getBoolean("is_blocked"),
                        rs.getString("blocker_note")))
                .list();
    }

    /** 作者 (在呼叫者有權限看的日報中) 最新的日報日期。 */
    public Optional<LocalDate> latestReportDate(String author, List<String> accessibleRoles) {
        return jdbc.sql("""
                SELECT MAX(report_date) FROM daily_reports
                WHERE author = :author AND role_access IN (:roles)
                """)
                .param("author", author)
                .param("roles", accessibleRoles)
                .query((rs, rowNum) -> rs.getDate(1) == null ? null : rs.getDate(1).toLocalDate())
                .list().stream().filter(java.util.Objects::nonNull).findFirst();
    }

    /** 資料庫中所有日報作者的姓名 (不含權限判斷，只用來辨識使用者問題裡提到的人名)。 */
    public List<String> findAuthors() {
        return jdbc.sql("SELECT DISTINCT author FROM daily_reports WHERE author IS NOT NULL ORDER BY author")
                .query((rs, rowNum) -> rs.getString(1))
                .list();
    }
}
