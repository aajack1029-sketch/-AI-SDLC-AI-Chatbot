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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DailyReportSqlRepositoryTests {

    private EmbeddedDatabase database;
    private DailyReportSqlRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:schema.sql")
                .build();
        repository = new DailyReportSqlRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    private static StructuredReport report(String id, String author, String date, StructuredTask... tasks) {
        return new StructuredReport(id, LocalDate.parse(date), author, "後端工程師", "Y專案",
                "DEVELOPER", List.of(tasks));
    }

    @Test
    void listsDistinctAuthorsInOrder() {
        repository.save(report("REP-A1", "陳大明", "2026-10-01",
                new StructuredTask("任務一", 10, TaskPriority.LOW, false, null)));
        repository.save(report("REP-A2", "陳大明", "2026-10-02",
                new StructuredTask("任務二", 20, TaskPriority.LOW, false, null)));
        repository.save(report("REP-A3", "林小華", "2026-10-02",
                new StructuredTask("任務三", 30, TaskPriority.LOW, false, null)));

        assertThat(repository.findAuthors()).containsExactlyInAnyOrder("陳大明", "林小華").hasSize(2);
    }

    @Test
    void savesReportAndReadsTasksBack() {
        repository.save(report("REP-1", "陳大明", "2026-10-01",
                new StructuredTask("建置 API", 60, TaskPriority.HIGH, false, null),
                new StructuredTask("修正登入", 0, TaskPriority.MEDIUM, true, "等待測試環境")));

        assertThat(repository.countReports()).isEqualTo(1);
        assertThat(repository.findTasksByReportId("REP-1"))
                .extracting(StructuredTask::taskName, StructuredTask::progressPercent,
                        StructuredTask::priority, StructuredTask::blocked, StructuredTask::blockerNote)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("建置 API", 60, TaskPriority.HIGH, false, null),
                        org.assertj.core.groups.Tuple.tuple("修正登入", 0, TaskPriority.MEDIUM, true, "等待測試環境"));
    }

    @Test
    void savingSameReportTwiceDoesNotDuplicateTasks() {
        StructuredReport first = report("REP-1", "陳大明", "2026-10-01",
                new StructuredTask("建置 API", 30, TaskPriority.HIGH, false, null));
        repository.save(first);
        repository.save(report("REP-1", "陳大明", "2026-10-01",
                new StructuredTask("建置 API", 80, TaskPriority.HIGH, false, null)));

        assertThat(repository.countReports()).isEqualTo(1);
        assertThat(repository.countTasks()).isEqualTo(1);
        assertThat(repository.findTasksByReportId("REP-1").get(0).progressPercent()).isEqualTo(80);
    }

    @Test
    void summarizesAverageProgressAndBlockedCountByAuthorWithinDateRange() {
        repository.save(report("REP-1", "陳大明", "2026-10-01",
                new StructuredTask("A", 20, TaskPriority.HIGH, true, "卡住"),
                new StructuredTask("B", 60, TaskPriority.LOW, false, null)));
        repository.save(report("REP-2", "林小華", "2026-10-02",
                new StructuredTask("C", 100, TaskPriority.MEDIUM, false, null)));
        repository.save(report("REP-3", "陳大明", "2026-11-30",
                new StructuredTask("D", 0, TaskPriority.LOW, false, null)));

        List<AuthorProgress> result =
                repository.summarizeByAuthor(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"));

        assertThat(result).hasSize(2);
        AuthorProgress chen = result.stream().filter(p -> p.author().equals("陳大明")).findFirst().orElseThrow();
        assertThat(chen.taskCount()).isEqualTo(2);
        assertThat(chen.averageProgressPercent()).isEqualTo(40.0);
        assertThat(chen.blockedCount()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidProgressAndBlankTaskName() {
        assertThatThrownBy(() -> new StructuredTask("X", 101, TaskPriority.HIGH, false, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredTask(" ", 10, TaskPriority.HIGH, false, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
