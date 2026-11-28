package com.example.demo.report;

import com.example.demo.report.DailyReportSqlRepository.TaskRow;
import com.example.demo.report.HandoverService.SortBy;
import com.example.demo.report.HandoverSummary.HandoverTask;
import com.example.demo.report.HandoverSummary.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HandoverServiceTests {

    private static TaskRow row(String reportId, String date, String name, int progress,
                               TaskPriority priority, boolean blocked, String note) {
        return new TaskRow(reportId, LocalDate.parse(date), name, progress, priority, blocked, note);
    }

    // ---- 純邏輯：去重、狀態、排序 ----

    @Test
    void sameTaskPlannedThenCompletedIsMergedAndKeepsTheLatestState() {
        HandoverSummary summary = HandoverService.build("林小華",
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-10"),
                List.of(
                        row("R1", "2026-10-01", "整理測試報告", 0, TaskPriority.LOW, false, null),
                        row("R2", "2026-10-02", "整理測試報告", 100, TaskPriority.LOW, false, null)),
                SortBy.PRIORITY);

        assertThat(summary.rawTaskCount()).isEqualTo(2);
        assertThat(summary.duplicatesRemoved()).isEqualTo(1);
        assertThat(summary.tasks()).hasSize(1);
        HandoverTask task = summary.tasks().get(0);
        assertThat(task.status()).isEqualTo(TaskStatus.DONE);
        assertThat(task.occurrences()).isEqualTo(2);
        assertThat(task.reportId()).isEqualTo("R2");
    }

    @Test
    void sameWorkWithDifferentStatusVerbOrPrefixIsMerged() {
        assertThat(HandoverService.similar("部署 Qdrant 向量資料庫", "部署Qdrant向量資料庫")).isTrue();
        assertThat(HandoverService.similar("進行 GCP 雲端成本盤點", "完成 GCP 雲端成本盤點")).isTrue();
        assertThat(HandoverService.similar("請假至 A 客戶端做實體環境建置與系統展示", "實體環境建置與系統展示"))
                .isTrue();
    }

    @Test
    void differentTasksWithSimilarWordingAreNotMerged() {
        assertThat(HandoverService.similar("低優先度進行中任務", "中優先度進行中任務")).isFalse();
        assertThat(HandoverService.similar("搭建完成 GitHub MCP Server", "完成 GitHub MCP Server 權限設定"))
                .isFalse();
        assertThat(HandoverService.similar("更新 Prompt Injection 測試", "收集 Prompt Injection 越獄語句"))
                .isFalse();
        assertThat(HandoverService.similar("部署 Qdrant 向量資料庫", "部署 Docker 環境")).isFalse();
        assertThat(HandoverService.similar("修正登入流程", "整理測試報告")).isFalse();
        assertThat(HandoverService.similar("", "任務")).isFalse();
    }

    @Test
    void classifiesTaskStatus() {
        assertThat(HandoverService.statusOf(row("R", "2026-10-01", "a", 100, TaskPriority.LOW, false, null)))
                .isEqualTo(TaskStatus.DONE);
        assertThat(HandoverService.statusOf(row("R", "2026-10-01", "a", 50, TaskPriority.LOW, false, null)))
                .isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(HandoverService.statusOf(row("R", "2026-10-01", "a", 0, TaskPriority.LOW, false, null)))
                .isEqualTo(TaskStatus.NOT_STARTED);
        assertThat(HandoverService.statusOf(row("R", "2026-10-01", "a", 0, TaskPriority.LOW, true, "等待")))
                .isEqualTo(TaskStatus.BLOCKED);
        assertThat(HandoverService.statusOf(row("R", "2026-10-01", "a", 100, TaskPriority.LOW, true, "舊的")))
                .isEqualTo(TaskStatus.DONE);
    }

    @Test
    void openTasksComeFirstThenSortedByPriorityOrProgress() {
        List<TaskRow> rows = List.of(
                row("R1", "2026-10-01", "上線前的資安檢查", 100, TaskPriority.HIGH, false, null),
                row("R1", "2026-10-01", "整理客戶需求文件", 80, TaskPriority.LOW, false, null),
                row("R1", "2026-10-01", "修復付款漏洞", 0, TaskPriority.HIGH, false, null),
                row("R1", "2026-10-01", "開發報表匯出功能", 20, TaskPriority.MEDIUM, false, null));

        HandoverSummary byPriority = HandoverService.build("a", null, null, rows, SortBy.PRIORITY);
        assertThat(byPriority.tasks()).extracting(HandoverTask::taskName).containsExactly(
                "修復付款漏洞", "開發報表匯出功能", "整理客戶需求文件", "上線前的資安檢查");

        HandoverSummary byProgress = HandoverService.build("a", null, null, rows, SortBy.PROGRESS);
        assertThat(byProgress.tasks()).extracting(HandoverTask::taskName).containsExactly(
                "修復付款漏洞", "開發報表匯出功能", "整理客戶需求文件", "上線前的資安檢查");
    }

    @Test
    void statusCountsCoverAllStatuses() {
        HandoverSummary summary = HandoverService.build("a", null, null, List.of(
                row("R", "2026-10-01", "任務甲", 100, TaskPriority.LOW, false, null),
                row("R", "2026-10-01", "任務乙", 40, TaskPriority.LOW, false, null),
                row("R", "2026-10-01", "任務丙", 0, TaskPriority.LOW, true, "等待核准"),
                row("R", "2026-10-01", "任務丁", 0, TaskPriority.LOW, false, null)), SortBy.PRIORITY);

        assertThat(summary.statusCounts()).containsEntry(TaskStatus.DONE, 1)
                .containsEntry(TaskStatus.IN_PROGRESS, 1)
                .containsEntry(TaskStatus.BLOCKED, 1)
                .containsEntry(TaskStatus.NOT_STARTED, 1);
        assertThat(summary.totalTasks()).isEqualTo(4);
    }

    // ---- 搭配 H2：權限與日期預設 ----

    private EmbeddedDatabase database;
    private DailyReportSqlRepository repository;
    private HandoverService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:schema.sql")
                .build();
        repository = new DailyReportSqlRepository(database);
        service = new HandoverService(repository);
        repository.save(report("R1", "2026-10-01", "林小華", "DEVELOPER",
                new StructuredTask("建立金流模組", 60, TaskPriority.HIGH, false, null)));
        repository.save(report("R2", "2026-10-20", "林小華", "DEVELOPER",
                new StructuredTask("建立金流模組", 100, TaskPriority.HIGH, false, null),
                new StructuredTask("部署環境", 0, TaskPriority.HIGH, true, "等待權限核准")));
        repository.save(report("R3", "2026-10-21", "林小華", "MANAGER",
                new StructuredTask("主管限定的決策任務", 0, TaskPriority.HIGH, false, null)));
        repository.save(report("R4", "2026-10-20", "陳大明", "DEVELOPER",
                new StructuredTask("重構元件", 30, TaskPriority.MEDIUM, false, null)));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    private static StructuredReport report(String id, String date, String author, String access,
                                           StructuredTask... tasks) {
        return new StructuredReport(id, LocalDate.parse(date), author, null, null, access, List.of(tasks));
    }

    @Test
    void developerSeesOwnTasksOnlyFromDeveloperAccessibleReports() {
        HandoverSummary summary = service.summarize("DEVELOPER", "林小華", null, null, null, SortBy.PRIORITY);

        assertThat(summary.author()).isEqualTo("林小華");
        assertThat(summary.tasks()).extracting(HandoverTask::taskName)
                .doesNotContain("主管限定的決策任務")
                .contains("建立金流模組", "部署環境");
        // 預設區間：以作者最新 (可見) 日報 10/20 為終點往前 14 天 → 10/07 起，所以 10/01 那筆不算
        assertThat(summary.to()).isEqualTo(LocalDate.parse("2026-10-20"));
        assertThat(summary.from()).isEqualTo(LocalDate.parse("2026-10-07"));
        assertThat(summary.rawTaskCount()).isEqualTo(2);
    }

    @Test
    void managerCanSeeManagerOnlyReportsAndOtherAuthors() {
        HandoverSummary own = service.summarize("MANAGER", "黃雅婷", "林小華", null, null, SortBy.PRIORITY);
        assertThat(own.tasks()).extracting(HandoverTask::taskName).contains("主管限定的決策任務");

        HandoverSummary other = service.summarize("MANAGER", "黃雅婷", "陳大明", null, null, SortBy.PRIORITY);
        assertThat(other.tasks()).extracting(HandoverTask::taskName).containsExactly("重構元件");
    }

    @Test
    void developerCannotReadAnotherAuthorsSummary() {
        assertThatThrownBy(() ->
                service.summarize("DEVELOPER", "林小華", "陳大明", null, null, SortBy.PRIORITY))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void developerWithoutAuthorMappingIsDenied() {
        assertThatThrownBy(() ->
                service.summarize("DEVELOPER", null, null, null, null, SortBy.PRIORITY))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void managerMustSpecifyAnAuthorWhenHasNoMapping() {
        assertThatThrownBy(() ->
                service.summarize("MANAGER", null, null, null, null, SortBy.PRIORITY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownAuthorReturnsEmptySummary() {
        HandoverSummary summary = service.summarize("MANAGER", null, "不存在的人", null, null, SortBy.PRIORITY);

        assertThat(summary.tasks()).isEmpty();
        assertThat(summary.totalTasks()).isZero();
    }

    @Test
    void explicitDateRangeIsRespected() {
        HandoverSummary summary = service.summarize("DEVELOPER", "林小華", null,
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02"), SortBy.PRIORITY);

        assertThat(summary.rawTaskCount()).isEqualTo(1);
        assertThat(summary.tasks().get(0).progressPercent()).isEqualTo(60);
    }

    @Test
    void rejectsReversedDateRange() {
        assertThatThrownBy(() -> service.summarize("DEVELOPER", "林小華", null,
                LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-01"), SortBy.PRIORITY))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
