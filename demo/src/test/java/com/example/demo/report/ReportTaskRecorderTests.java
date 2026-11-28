package com.example.demo.report;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportTaskRecorderTests {

    private EmbeddedDatabase database;
    private DailyReportSqlRepository repository;
    private TaskExtractionService extractionService;
    private ReportTaskRecorder recorder;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:schema.sql")
                .build();
        repository = new DailyReportSqlRepository(database);
        extractionService = mock(TaskExtractionService.class);
        recorder = new ReportTaskRecorder(extractionService, repository);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void savesExtractedTasksForTheReport() {
        when(extractionService.extractFromText(anyString())).thenReturn(List.of(
                new StructuredTask("完成金流測試", 100, TaskPriority.HIGH, false, null),
                new StructuredTask("撰寫報告", 0, TaskPriority.LOW, true, "等待核准")));

        int saved = recorder.record("REP-1", LocalDate.of(2026, 11, 28), "林小華", "DEVELOPER", "原文");

        assertThat(saved).isEqualTo(2);
        assertThat(repository.countReports()).isEqualTo(1);
        assertThat(repository.findTasksByReportId("REP-1"))
                .extracting(StructuredTask::taskName, StructuredTask::blocked)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("完成金流測試", false),
                        org.assertj.core.groups.Tuple.tuple("撰寫報告", true));
    }

    @Test
    void recordingTheSameReportAgainReplacesItsTasks() {
        when(extractionService.extractFromText(anyString()))
                .thenReturn(List.of(new StructuredTask("舊任務", 10, TaskPriority.LOW, false, null)))
                .thenReturn(List.of(new StructuredTask("新任務", 90, TaskPriority.HIGH, false, null)));

        recorder.record("REP-1", LocalDate.of(2026, 11, 28), "林小華", "DEVELOPER", "第一版");
        recorder.record("REP-1", LocalDate.of(2026, 11, 28), "林小華", "DEVELOPER", "第二版");

        assertThat(repository.countTasks()).isEqualTo(1);
        assertThat(repository.findTasksByReportId("REP-1").get(0).taskName()).isEqualTo("新任務");
    }

    @Test
    void acceptsAReportWithNoTasks() {
        when(extractionService.extractFromText(anyString())).thenReturn(List.of());

        assertThat(recorder.record("REP-2", LocalDate.of(2026, 11, 28), "林小華", "DEVELOPER", "閒聊"))
                .isZero();
        assertThat(repository.countReports()).isEqualTo(1);
        assertThat(repository.countTasks()).isZero();
    }
}
