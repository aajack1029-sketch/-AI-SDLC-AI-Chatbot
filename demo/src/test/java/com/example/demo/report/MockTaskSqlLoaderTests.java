package com.example.demo.report;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockTaskSqlLoaderTests {

    private static final String REPORTS = """
            [
              {"report_id":"R1","date":"2026-10-01","author":"陳大明","role":"前端","role_access":"DEVELOPER","project":"X專案"},
              {"report_id":"R2","date":"2026-10-02","author":"林小華","role":"雲端","role_access":"DEVELOPER","project":"維運"},
              {"report_id":"R3","date":"2026-10-03","author":"林小華","role":"雲端","role_access":"DEVELOPER","project":"維運"}
            ]
            """;

    private static final String TASKS = """
            [
              {"report_id":"R1","tasks":[
                {"task_name":"建立元件","progress_percent":100,"priority":"MEDIUM","blocked":false,"blocker_note":null},
                {"task_name":"修正排版","progress_percent":30,"priority":"HIGH","blocked":true,"blocker_note":"等設計稿"}]},
              {"report_id":"R2","tasks":[
                {"task_name":"部署 Qdrant","progress_percent":50,"priority":"unknown","blocked":false}]}
            ]
            """;

    private EmbeddedDatabase database;
    private DailyReportSqlRepository repository;
    private MockTaskSqlLoader loader;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:schema.sql")
                .build();
        repository = new DailyReportSqlRepository(database);
        loader = new MockTaskSqlLoader(repository, true, 0);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    private static ByteArrayInputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void importsReportsThatHaveExtractedTasksAndSkipsTheRest() throws IOException {
        int imported = loader.load(stream(REPORTS), stream(TASKS), 0);

        assertThat(imported).isEqualTo(2);
        assertThat(repository.countReports()).isEqualTo(2);
        assertThat(repository.countTasks()).isEqualTo(3);
        assertThat(repository.findTasksByReportId("R1"))
                .extracting(StructuredTask::taskName, StructuredTask::blocked, StructuredTask::blockerNote)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("建立元件", false, null),
                        org.assertj.core.groups.Tuple.tuple("修正排版", true, "等設計稿"));
    }

    @Test
    void unknownPriorityFallsBackToMedium() throws IOException {
        loader.load(stream(REPORTS), stream(TASKS), 0);

        assertThat(repository.findTasksByReportId("R2").get(0).priority()).isEqualTo(TaskPriority.MEDIUM);
    }

    @Test
    void importingTwiceDoesNotDuplicateTasks() throws IOException {
        loader.load(stream(REPORTS), stream(TASKS), 0);
        loader.load(stream(REPORTS), stream(TASKS), 0);

        assertThat(repository.countTasks()).isEqualTo(3);
    }

    @Test
    void respectsIngestionLimit() throws IOException {
        int imported = loader.load(stream(REPORTS), stream(TASKS), 1);

        assertThat(imported).isEqualTo(1);
        assertThat(repository.countReports()).isEqualTo(1);
    }

    @Test
    void sqlAggregatesMatchImportedData() throws IOException {
        loader.load(stream(REPORTS), stream(TASKS), 0);

        List<AuthorProgress> result =
                repository.summarizeByAuthor(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

        AuthorProgress chen = result.stream().filter(p -> p.author().equals("陳大明")).findFirst().orElseThrow();
        assertThat(chen.taskCount()).isEqualTo(2);
        assertThat(chen.averageProgressPercent()).isEqualTo(65.0);
        assertThat(chen.blockedCount()).isEqualTo(1);
    }
}
