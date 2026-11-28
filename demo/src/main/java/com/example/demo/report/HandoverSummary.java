package com.example.demo.report;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 交接摘要 API 的回傳內容。所有數字都由 SQL 與程式計算，不經過 LLM。 */
public record HandoverSummary(
        String author,
        LocalDate from,
        LocalDate to,
        int rawTaskCount,
        int duplicatesRemoved,
        int totalTasks,
        Map<TaskStatus, Integer> statusCounts,
        List<HandoverTask> tasks) {

    public enum TaskStatus {
        DONE, IN_PROGRESS, BLOCKED, NOT_STARTED
    }

    public record HandoverTask(
            String taskName,
            int progressPercent,
            TaskPriority priority,
            TaskStatus status,
            String blockerNote,
            LocalDate lastReportDate,
            String reportId,
            int occurrences) {
    }
}
