package com.example.demo.report;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

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
}
