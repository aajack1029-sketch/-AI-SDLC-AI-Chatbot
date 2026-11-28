package com.example.demo.report;

import com.example.demo.ChatStatus;
import com.example.demo.report.HandoverSummary.HandoverTask;
import com.example.demo.report.HandoverSummary.TaskStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HandoverChatHandlerTests {

    private static final LocalDate FROM = LocalDate.parse("2026-11-15");
    private static final LocalDate TO = LocalDate.parse("2026-11-28");
    private static final List<String> AUTHORS = List.of("林小華", "陳大明", "黃雅婷");

    private static HandoverTask task(String name, int progress, TaskPriority priority,
                                     TaskStatus status, String blocker) {
        return new HandoverTask(name, progress, priority, status, blocker, TO, "REP-1", 1);
    }

    private static HandoverSummary summary(List<HandoverTask> tasks) {
        Map<TaskStatus, Integer> counts = new EnumMap<>(TaskStatus.class);
        for (TaskStatus status : TaskStatus.values()) {
            counts.put(status, (int) tasks.stream().filter(t -> t.status() == status).count());
        }
        return new HandoverSummary("林小華", FROM, TO, tasks.size(), 0, tasks.size(), counts, tasks);
    }

    private static List<HandoverTask> sampleTasks() {
        return List.of(
                task("部署環境", 0, TaskPriority.HIGH, TaskStatus.BLOCKED, "等待權限核准"),
                task("撰寫 API 文件", 50, TaskPriority.LOW, TaskStatus.IN_PROGRESS, null),
                task("整理測試報告", 0, TaskPriority.MEDIUM, TaskStatus.NOT_STARTED, null),
                task("建置資料庫", 100, TaskPriority.HIGH, TaskStatus.DONE, null));
    }

    private static DailyReportSqlRepository repositoryWithAuthors() {
        DailyReportSqlRepository repository = mock(DailyReportSqlRepository.class);
        when(repository.findAuthors()).thenReturn(AUTHORS);
        return repository;
    }

    @Test
    void detectsTheMentionedAuthorAndPrefersTheLongestMatch() {
        assertThat(HandoverChatHandler.detectAuthor("幫我整理陳大明的交接", AUTHORS)).contains("陳大明");
        assertThat(HandoverChatHandler.detectAuthor("幫我整理我的交接", AUTHORS)).isEmpty();
        assertThat(HandoverChatHandler.detectAuthor("整理小明的交接", List.of("小明", "王小明")))
                .contains("小明");
        assertThat(HandoverChatHandler.detectAuthor("整理王小明的交接", List.of("小明", "王小明")))
                .contains("王小明");
        assertThat(HandoverChatHandler.detectAuthor(null, AUTHORS)).isEmpty();
    }

    @Test
    void formatsFactsFromSqlSummaryWithoutHelpFromTheModel() {
        String text = HandoverChatHandler.format(summary(sampleTasks()));

        assertThat(text).contains(
                "【林小華 的交接摘要】2026-11-15 ～ 2026-11-28",
                "共 4 項任務：已完成 1、進行中 1、卡關 1、未開始 1",
                "待交接任務（共 3 項，依優先度排序）",
                "1. [高] 部署環境 — 卡關 0%，卡關原因：等待權限核准",
                "2. [低] 撰寫 API 文件 — 進行中 50%",
                "3. [中] 整理測試報告 — 未開始 0%",
                "已完成：建置資料庫");
    }

    @Test
    void truncatesLongOpenTaskLists() {
        List<HandoverTask> tasks = new ArrayList<>();
        for (int index = 1; index <= 13; index++) {
            tasks.add(task("任務" + index, 10, TaskPriority.MEDIUM, TaskStatus.IN_PROGRESS, null));
        }

        String text = HandoverChatHandler.format(summary(tasks));

        assertThat(text).contains("共 13 項", "10. [中] 任務10", "另有 3 項未列出");
        assertThat(text).doesNotContain("11. [中]");
    }

    @Test
    void reportsWhenThereIsNothingToHandOver() {
        HandoverSummary empty = summary(List.of());
        assertThat(HandoverChatHandler.format(empty)).contains("沒有可顯示的任務");

        HandoverSummary noReports = new HandoverSummary("林小華", null, null, 0, 0, 0, Map.of(), List.of());
        assertThat(HandoverChatHandler.format(noReports)).contains("沒有可查詢的日報任務");
    }

    @Test
    void appendsModelAdviceButSendsOnlyTheSqlFactsToTheModel() {
        HandoverService service = mock(HandoverService.class);
        when(service.summarize(eq("DEVELOPER"), eq("林小華"), isNull(), isNull(), isNull(), any()))
                .thenReturn(summary(sampleTasks()));
        AtomicReference<String> sentToModel = new AtomicReference<>();
        HandoverChatHandler handler = new HandoverChatHandler(
                service, repositoryWithAuthors(), mock(AuthorResolver.class), text -> {
                    sentToModel.set(text);
                    return "・先處理「部署環境」的權限申請。";
                });

        HandoverChatHandler.Reply reply = handler.handle("幫我整理我的交接", "DEVELOPER", "林小華");

        assertThat(reply.status()).isEqualTo(ChatStatus.SUCCESS);
        assertThat(reply.answer()).startsWith("【林小華 的交接摘要】");
        assertThat(reply.answer()).contains("共 4 項任務", "💡 交接建議", "先處理「部署環境」的權限申請");
        assertThat(sentToModel.get()).contains("共 4 項任務：已完成 1、進行中 1、卡關 1、未開始 1");
    }

    @Test
    void stillReturnsSqlFactsWhenTheModelFails() {
        HandoverService service = mock(HandoverService.class);
        when(service.summarize(any(), any(), any(), any(), any(), any()))
                .thenReturn(summary(sampleTasks()));
        HandoverChatHandler handler = new HandoverChatHandler(
                service, repositoryWithAuthors(), mock(AuthorResolver.class), text -> {
                    throw new IllegalStateException("Gemini unavailable");
                });

        HandoverChatHandler.Reply reply = handler.handle("整理林小華的交接", "MANAGER", "黃雅婷");

        assertThat(reply.status()).isEqualTo(ChatStatus.SUCCESS);
        assertThat(reply.answer()).contains("共 4 項任務", "部署環境");
        assertThat(reply.answer()).doesNotContain("交接建議");
    }

    @Test
    void doesNotCallTheModelWhenThereAreNoTasks() {
        HandoverService service = mock(HandoverService.class);
        when(service.summarize(any(), any(), any(), any(), any(), any()))
                .thenReturn(summary(List.of()));
        AtomicReference<String> sentToModel = new AtomicReference<>();
        HandoverChatHandler handler = new HandoverChatHandler(
                service, repositoryWithAuthors(), mock(AuthorResolver.class), text -> {
                    sentToModel.set(text);
                    return "不應被呼叫";
                });

        HandoverChatHandler.Reply reply = handler.handle("整理我的交接", "DEVELOPER", "林小華");

        assertThat(reply.answer()).contains("沒有可顯示的任務");
        assertThat(sentToModel.get()).isNull();
    }

    @Test
    void passesTheMentionedAuthorToTheServiceSoManagersCanQueryAnyone() {
        HandoverService service = mock(HandoverService.class);
        when(service.summarize(any(), any(), any(), any(), any(), any()))
                .thenReturn(summary(sampleTasks()));
        HandoverChatHandler handler = new HandoverChatHandler(
                service, repositoryWithAuthors(), mock(AuthorResolver.class), text -> "・建議");

        handler.handle("幫我整理陳大明的交接", "MANAGER", "黃雅婷");

        verify(service).summarize(eq("MANAGER"), eq("黃雅婷"), eq("陳大明"), isNull(), isNull(), any());
    }

    @Test
    void developerAskingForAnotherPersonIsDeniedByLayer2WithoutCallingTheModel() {
        // 使用真正的 HandoverService：員工查別人必須被擋下，而且不會碰資料也不會呼叫 Gemini。
        DailyReportSqlRepository repository = repositoryWithAuthors();
        HandoverService realService = new HandoverService(repository);
        HandoverChatHandler handler = new HandoverChatHandler(
                realService, repository, mock(AuthorResolver.class), text -> {
                    throw new AssertionError("模型不應被呼叫");
                });

        HandoverChatHandler.Reply reply = handler.handle("幫我整理陳大明的交接", "DEVELOPER", "林小華");

        assertThat(reply.status()).isEqualTo(ChatStatus.RBAC_DENIED);
        assertThat(reply.answer()).contains("只能查詢自己的交接摘要");
    }

    @Test
    void developerWithoutAnAuthorMappingIsDenied() {
        DailyReportSqlRepository repository = repositoryWithAuthors();
        HandoverChatHandler handler = new HandoverChatHandler(
                new HandoverService(repository), repository, mock(AuthorResolver.class),
                text -> "・不應被呼叫");

        HandoverChatHandler.Reply reply = handler.handle("整理我的交接", "DEVELOPER", null);

        assertThat(reply.status()).isEqualTo(ChatStatus.RBAC_DENIED);
        assertThat(reply.answer()).contains("尚未對應到日報作者");
    }

    @Test
    void managerWithoutNameOrMappingIsAskedWhoToSummarize() {
        DailyReportSqlRepository repository = repositoryWithAuthors();
        HandoverChatHandler handler = new HandoverChatHandler(
                new HandoverService(repository), repository, mock(AuthorResolver.class),
                text -> "・不應被呼叫");

        HandoverChatHandler.Reply reply = handler.handle("幫我整理交接", "MANAGER", null);

        assertThat(reply.status()).isEqualTo(ChatStatus.VALIDATION_ERROR);
        assertThat(reply.answer()).contains("哪一位同仁");
    }
}
