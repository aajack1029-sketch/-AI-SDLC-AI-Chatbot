package com.example.demo.report;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/** 把使用者新增的日報拆成任務並寫入 SQL（與寫入 Qdrant 的語意內容互補）。 */
@Slf4j
@Component
public class ReportTaskRecorder {

    private final TaskExtractionService extractionService;
    private final DailyReportSqlRepository repository;

    public ReportTaskRecorder(
            TaskExtractionService extractionService, DailyReportSqlRepository repository) {
        this.extractionService = extractionService;
        this.repository = repository;
    }

    /** 回傳寫入的任務數。重複寫入同一個 reportId 會覆寫，不會產生重複任務。 */
    public int record(String reportId, LocalDate date, String author, String roleAccess, String rawText) {
        List<StructuredTask> tasks = extractionService.extractFromText(rawText);
        repository.save(new StructuredReport(reportId, date, author, null, null, roleAccess, tasks));
        log.info("日報 {} 已將 {} 個任務寫入 SQL：{}", reportId, tasks.size(),
                tasks.stream()
                        .map(t -> "%s(%d%%, %s%s)".formatted(
                                t.taskName(), t.progressPercent(), t.priority(), t.blocked() ? ", 卡關" : ""))
                        .collect(Collectors.joining("；")));
        return tasks.size();
    }
}
