package com.example.demo.report;

import java.util.Objects;

/** 日報中的單一任務（供 SQL 統計與進度圖使用）。 */
public record StructuredTask(
        String taskName,
        int progressPercent,
        TaskPriority priority,
        boolean blocked,
        String blockerNote) {

    public StructuredTask {
        if (taskName == null || taskName.isBlank()) {
            throw new IllegalArgumentException("taskName must not be blank");
        }
        if (progressPercent < 0 || progressPercent > 100) {
            throw new IllegalArgumentException("progressPercent must be between 0 and 100");
        }
        taskName = taskName.trim();
        priority = Objects.requireNonNullElse(priority, TaskPriority.MEDIUM);
        blockerNote = blockerNote == null || blockerNote.isBlank() ? null : blockerNote.trim();
    }
}
