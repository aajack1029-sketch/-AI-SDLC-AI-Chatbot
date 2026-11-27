package com.example.demo.report;

import com.example.demo.report.TaskExtractionService.TaskExtraction;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskExtractionServiceTests {

    @Test
    void clampsProgressAndFillsDefaults() {
        List<StructuredTask> tasks = TaskExtractionService.toStructuredTasks(List.of(
                new TaskExtraction("任務 A", 150, TaskPriority.HIGH, false, "不該保留"),
                new TaskExtraction("任務 B", -5, null, null, null),
                new TaskExtraction("任務 C", null, TaskPriority.LOW, true, " 等待核准 ")));

        assertThat(tasks).extracting(StructuredTask::progressPercent).containsExactly(100, 0, 0);
        assertThat(tasks).extracting(StructuredTask::priority)
                .containsExactly(TaskPriority.HIGH, TaskPriority.MEDIUM, TaskPriority.LOW);
        assertThat(tasks).extracting(StructuredTask::blocked).containsExactly(false, false, true);
        assertThat(tasks).extracting(StructuredTask::blockerNote).containsExactly(null, null, "等待核准");
    }

    @Test
    void dropsBlankAndNullTasks() {
        List<StructuredTask> tasks = TaskExtractionService.toStructuredTasks(Arrays.asList(
                null,
                new TaskExtraction("  ", 10, TaskPriority.LOW, false, null),
                new TaskExtraction(null, 10, TaskPriority.LOW, false, null),
                new TaskExtraction("有效任務", 10, TaskPriority.LOW, false, null)));

        assertThat(tasks).extracting(StructuredTask::taskName).containsExactly("有效任務");
    }
}
