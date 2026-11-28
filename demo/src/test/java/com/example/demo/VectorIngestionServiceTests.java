package com.example.demo;

import com.example.demo.dto.ExtractedDailyReport;
import com.example.demo.dto.RoleAccess;
import com.example.demo.report.ReportTaskRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VectorIngestionServiceTests {

    @Test
    void skipsIngestionWhenDisabled() {
        VectorStore knowledgeDocsVectorStore = mock(VectorStore.class);
        VectorStore dailyReportsVectorStore = mock(VectorStore.class);
        VectorIngestionService service =
                new VectorIngestionService(
                        knowledgeDocsVectorStore,
                        dailyReportsVectorStore,
                        new ObjectMapper(),
                        chatClientBuilder(),
                        false,
                        0);

        assertThatCode(() -> service.run()).doesNotThrowAnyException();
        verifyNoInteractions(knowledgeDocsVectorStore, dailyReportsVectorStore);
    }

    @Test
    void writesReportAccessLabelIntoDailyReportMetadata() throws Exception {
        VectorStore knowledgeDocsVectorStore = mock(VectorStore.class);
        VectorStore dailyReportsVectorStore = mock(VectorStore.class);
        VectorIngestionService service = new VectorIngestionService(
                knowledgeDocsVectorStore,
                dailyReportsVectorStore,
                new ObjectMapper(),
                chatClientBuilder(),
                true,
                1);

        service.run();

        verify(dailyReportsVectorStore).add(argThat(documents ->
                documents.size() == 1
                        && "DEVELOPER".equals(documents.get(0).getMetadata().get("role_access"))));
    }

    @Test
    void writesSyntheticManagerReportWithManagerAccessLabel() throws Exception {
        VectorStore knowledgeDocsVectorStore = mock(VectorStore.class);
        VectorStore dailyReportsVectorStore = mock(VectorStore.class);
        VectorIngestionService service = new VectorIngestionService(
                knowledgeDocsVectorStore,
                dailyReportsVectorStore,
                new ObjectMapper(),
                chatClientBuilder(),
                true,
                0);

        service.run();

        verify(dailyReportsVectorStore, atLeastOnce()).add(argThat(documents ->
                documents.stream()
                        .filter(document -> "REP-MOCK-MANAGER-20261102-01"
                                .equals(document.getMetadata().get("report_id")))
                        .anyMatch(document -> "MANAGER"
                                .equals(document.getMetadata().get("role_access")))));
    }

    @Test
    void marksManagerPerformanceAuditReportsAsManagerOnly() throws Exception {
        VectorStore knowledgeDocsVectorStore = mock(VectorStore.class);
        VectorStore dailyReportsVectorStore = mock(VectorStore.class);
        VectorIngestionService service = new VectorIngestionService(
                knowledgeDocsVectorStore,
                dailyReportsVectorStore,
                new ObjectMapper(),
                chatClientBuilder(),
                true,
                0);

        service.run();

        verify(dailyReportsVectorStore, atLeastOnce()).add(argThat(documents ->
                documents.stream().anyMatch(document ->
                        "REP-20261006-04".equals(document.getMetadata().get("report_id"))
                                && "MANAGER".equals(document.getMetadata().get("role_access")))));
        verify(dailyReportsVectorStore, atLeastOnce()).add(argThat(documents ->
                documents.stream().anyMatch(document ->
                        "REP-20261016-04".equals(document.getMetadata().get("report_id"))
                                && "MANAGER".equals(document.getMetadata().get("role_access")))));
    }

    @Test
    void enforcesAuthenticatedRoleWhenWritingAnExtractedReport() {
        VectorStore knowledgeDocsVectorStore = mock(VectorStore.class);
        VectorStore dailyReportsVectorStore = mock(VectorStore.class);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ExtractedDailyReport extracted = new ExtractedDailyReport(
                "REP-20261120-01",
                "林小華",
                LocalDate.of(2026, 11, 20),
                RoleAccess.MANAGER,
                "192.168.1.208",
                "完成金流模組測試");
        when(chatClient.prompt()
                        .system(anyString())
                        .user(anyString())
                        .call()
                        .entity(ExtractedDailyReport.class))
                .thenReturn(extracted);
        VectorIngestionService service = new VectorIngestionService(
                knowledgeDocsVectorStore,
                dailyReportsVectorStore,
                new ObjectMapper().findAndRegisterModules(),
                chatClientBuilder(chatClient),
                false,
                0);

        ExtractedDailyReport result =
                service.ingestRawReport("我是林小華，金流測試完成", RoleAccess.DEVELOPER);

        assertThat(result.roleAccess()).isEqualTo(RoleAccess.DEVELOPER);
        assertThat(result.ipAddress()).isEqualTo("192.168.1.208");
        verify(dailyReportsVectorStore).add(argThat(documents ->
                documents.size() == 1
                        && "DEVELOPER".equals(
                                documents.get(0).getMetadata().get("role_access"))
                        && "192.168.1.208".equals(
                                documents.get(0).getMetadata().get("ip_address"))));
        verifyNoInteractions(knowledgeDocsVectorStore);
    }

    private VectorIngestionService serviceWithExtractedReport(ReportTaskRecorder recorder) {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt()
                        .system(anyString())
                        .user(anyString())
                        .call()
                        .entity(ExtractedDailyReport.class))
                .thenReturn(new ExtractedDailyReport(
                        "REP-20261120-01",
                        "林小華",
                        LocalDate.of(2026, 11, 20),
                        RoleAccess.MANAGER,
                        "192.168.1.208",
                        "完成金流模組測試"));
        VectorIngestionService service = new VectorIngestionService(
                mock(VectorStore.class),
                mock(VectorStore.class),
                new ObjectMapper().findAndRegisterModules(),
                chatClientBuilder(chatClient),
                false,
                0);
        service.setTaskRecorder(recorder);
        return service;
    }

    @Test
    void recordsTasksIntoSqlWithTheAuthenticatedRole() {
        ReportTaskRecorder recorder = mock(ReportTaskRecorder.class);
        VectorIngestionService service = serviceWithExtractedReport(recorder);

        service.ingestRawReport("我是林小華，金流測試完成", RoleAccess.DEVELOPER);

        verify(recorder).record(
                eq("REP-20261120-01"),
                eq(LocalDate.of(2026, 11, 20)),
                eq("林小華"),
                eq("DEVELOPER"),
                eq("我是林小華，金流測試完成"));
    }

    @Test
    void stillReturnsTheReportWhenSqlRecordingFails() {
        ReportTaskRecorder recorder = mock(ReportTaskRecorder.class);
        doThrow(new IllegalStateException("gemini down"))
                .when(recorder).record(anyString(), any(LocalDate.class), anyString(), anyString(), anyString());
        VectorIngestionService service = serviceWithExtractedReport(recorder);

        ExtractedDailyReport result =
                service.ingestRawReport("我是林小華，金流測試完成", RoleAccess.DEVELOPER);

        assertThat(result.reportId()).isEqualTo("REP-20261120-01");
    }

    @Test
    void authenticatedAuthorOverridesTheAuthorReadFromTheText() {
        VectorStore dailyReportsVectorStore = mock(VectorStore.class);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString()).call()
                        .entity(ExtractedDailyReport.class))
                .thenReturn(new ExtractedDailyReport(
                        null, "陳大明", LocalDate.of(2026, 11, 20), RoleAccess.DEVELOPER, "NONE", "完成測試"));
        VectorIngestionService service = new VectorIngestionService(
                mock(VectorStore.class), dailyReportsVectorStore, new ObjectMapper().findAndRegisterModules(),
                chatClientBuilder(chatClient), false, 0);

        ExtractedDailyReport result =
                service.ingestRawReport("我是陳大明，完成測試", RoleAccess.DEVELOPER, "林小華");

        assertThat(result.author()).isEqualTo("林小華");
        verify(dailyReportsVectorStore).add(argThat(documents ->
                "林小華".equals(documents.get(0).getMetadata().get("author"))));
    }

    private static ChatClient.Builder chatClientBuilder() {
        return chatClientBuilder(mock(ChatClient.class));
    }

    private static ChatClient.Builder chatClientBuilder(ChatClient chatClient) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(chatClient);
        return builder;
    }
}
