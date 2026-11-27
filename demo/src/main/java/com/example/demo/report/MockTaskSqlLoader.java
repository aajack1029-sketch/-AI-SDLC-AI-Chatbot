package com.example.demo.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 啟動時把 mock_daily_tasks.json (Gemini 抽取結果) 與 mock_daily_reports.json (日報基本資料)
 * 匯入 SQL。重複匯入是安全的 (以 report_id 覆寫)。不會呼叫 Gemini。
 */
@Slf4j
@Component
public class MockTaskSqlLoader implements CommandLineRunner {

    private final DailyReportSqlRepository repository;
    private final boolean enabled;
    private final int limit;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MockTaskSqlLoader(
            DailyReportSqlRepository repository,
            @Value("${app.sql.import-mock-tasks:true}") boolean enabled,
            @Value("${app.ingestion.limit:0}") int limit) {
        this.repository = repository;
        this.enabled = enabled;
        this.limit = limit;
    }

    @Override
    public void run(String... args) throws Exception {
        if (!enabled) {
            log.info("已停用 mock 日報任務匯入 SQL (app.sql.import-mock-tasks=false)");
            return;
        }
        try (InputStream reports = getClass().getResourceAsStream("/Data/mock_daily_reports.json");
                InputStream tasks = getClass().getResourceAsStream("/Data/mock_daily_tasks.json")) {
            if (reports == null || tasks == null) {
                log.warn("找不到 /Data/mock_daily_tasks.json，略過匯入 SQL。"
                        + "請先執行 Gemini 抽取工具 (app.tools.extract-mock-tasks=true)。");
                return;
            }
            int imported = load(reports, tasks, limit);
            log.info("已將 {} 筆日報的任務匯入 SQL (共 {} 個任務)", imported, repository.countTasks());
            for (AuthorProgress progress
                    : repository.summarizeByAuthor(LocalDate.of(2000, 1, 1), LocalDate.of(2100, 1, 1))) {
                log.info("SQL 統計 | {}：任務 {} 個，平均進度 {}%，卡關 {} 個",
                        progress.author(), progress.taskCount(),
                        Math.round(progress.averageProgressPercent() * 10) / 10.0,
                        progress.blockedCount());
            }
        }
    }

    /** 回傳成功匯入的日報數。limit &gt; 0 時只處理前 limit 筆日報。 */
    int load(InputStream reportsJson, InputStream tasksJson, int limit) throws IOException {
        List<Map<String, Object>> reports = objectMapper.readValue(reportsJson, new TypeReference<>() {});
        List<Map<String, Object>> taskEntries = objectMapper.readValue(tasksJson, new TypeReference<>() {});

        Map<String, List<StructuredTask>> tasksByReport = new HashMap<>();
        for (Map<String, Object> entry : taskEntries) {
            List<StructuredTask> tasks = new ArrayList<>();
            Object raw = entry.get("tasks");
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        tasks.add(new StructuredTask(
                                text(map.get("task_name")),
                                map.get("progress_percent") instanceof Number n ? n.intValue() : 0,
                                parsePriority(map.get("priority")),
                                Boolean.TRUE.equals(map.get("blocked")),
                                text(map.get("blocker_note"))));
                    }
                }
            }
            tasksByReport.put(String.valueOf(entry.get("report_id")), tasks);
        }

        List<Map<String, Object>> selected =
                limit > 0 && reports.size() > limit ? reports.subList(0, limit) : reports;
        int imported = 0;
        for (Map<String, Object> report : selected) {
            String reportId = String.valueOf(report.get("report_id"));
            List<StructuredTask> tasks = tasksByReport.get(reportId);
            if (tasks == null) {
                log.warn("日報 {} 沒有抽取結果，略過", reportId);
                continue;
            }
            repository.save(new StructuredReport(
                    reportId,
                    LocalDate.parse(String.valueOf(report.get("date"))),
                    String.valueOf(report.get("author")),
                    text(report.get("role")),
                    text(report.get("project")),
                    String.valueOf(report.get("role_access")),
                    tasks));
            imported++;
        }
        return imported;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static TaskPriority parsePriority(Object value) {
        if (value == null) {
            return TaskPriority.MEDIUM;
        }
        try {
            return TaskPriority.valueOf(value.toString().trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return TaskPriority.MEDIUM;
        }
    }
}
