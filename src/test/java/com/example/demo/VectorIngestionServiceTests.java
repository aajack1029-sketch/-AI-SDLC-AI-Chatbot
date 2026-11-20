package com.example.demo;

import com.example.demo.dto.ExtractedDailyReport;
import com.example.demo.dto.RoleAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VectorIngestionServiceTests {

    @Test
    void skipsIngestionWhenDisabled() {
        VectorStore vectorStore = mock(VectorStore.class);
        VectorIngestionService service = createService(
                vectorStore, mock(ChatClient.class), false, 0);

        assertThatCode(() -> service.run()).doesNotThrowAnyException();
        verifyNoInteractions(vectorStore);
    }

    @Test
    void writesReportAccessLabelIntoDailyReportMetadata() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        VectorIngestionService service = createService(
                vectorStore, mock(ChatClient.class), true, 1);

        service.run();

        verify(vectorStore).add(argThat(documents ->
                documents.size() == 1
                        && "DEVELOPER".equals(documents.get(0).getMetadata().get("role_access"))));
    }

    @Test
    void marksManagerPerformanceAuditReportsAsManagerOnly() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        VectorIngestionService service = createService(
                vectorStore, mock(ChatClient.class), true, 0);

        service.run();

        verify(vectorStore, atLeastOnce()).add(argThat(documents ->
                documents.stream().anyMatch(document ->
                        "REP-20261006-04".equals(document.getMetadata().get("report_id"))
                                && "MANAGER".equals(document.getMetadata().get("role_access")))));
        verify(vectorStore, atLeastOnce()).add(argThat(documents ->
                documents.stream().anyMatch(document ->
                        "REP-20261016-04".equals(document.getMetadata().get("report_id"))
                                && "MANAGER".equals(document.getMetadata().get("role_access")))));
    }

    @Test
    void storesTheAuthenticatedDeveloperRoleInsteadOfTheExtractedManagerRole() {
        VectorStore vectorStore = mock(VectorStore.class);
        ExtractedDailyReport extracted = extractedReport(
                "REP-20261120-01", RoleAccess.MANAGER);
        ChatClient chatClient = chatClientReturning(extracted);
        VectorIngestionService service = createService(vectorStore, chatClient, false, 0);

        ExtractedDailyReport result =
                service.ingestRawReport("完成測試環境部署", RoleAccess.DEVELOPER);

        assertThat(result.roleAccess()).isEqualTo(RoleAccess.DEVELOPER);
        verify(vectorStore).add(argThat(documents ->
                documents.size() == 1
                        && "DEVELOPER".equals(
                                documents.get(0).getMetadata().get("role_access"))));
    }

    @Test
    void storesTheAuthenticatedManagerRoleAndGeneratesMissingReportIds() {
        VectorStore vectorStore = mock(VectorStore.class);
        ExtractedDailyReport extracted = extractedReport(null, RoleAccess.DEVELOPER);
        VectorIngestionService service =
                createService(vectorStore, chatClientReturning(extracted), false, 0);

        ExtractedDailyReport result =
                service.ingestRawReport("完成專案風險評估", RoleAccess.MANAGER);

        assertThat(result.reportId()).startsWith("REP-20261120-");
        assertThat(result.roleAccess()).isEqualTo(RoleAccess.MANAGER);
        verify(vectorStore).add(argThat(documents ->
                documents.size() == 1
                        && "MANAGER".equals(
                                documents.get(0).getMetadata().get("role_access"))
                        && result.reportId().equals(
                                documents.get(0).getMetadata().get("report_id"))));
    }

    @Test
    void rejectsBlankInputOrMissingAuthenticatedRoleBeforeCallingGemini() {
        VectorStore vectorStore = mock(VectorStore.class);
        ChatClient chatClient = mock(ChatClient.class);
        VectorIngestionService service = createService(vectorStore, chatClient, false, 0);

        assertThatThrownBy(() -> service.ingestRawReport(" ", RoleAccess.DEVELOPER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ingestRawReport("日報", null))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(chatClient, vectorStore);
    }

    private static ExtractedDailyReport extractedReport(String reportId, RoleAccess roleAccess) {
        return new ExtractedDailyReport(
                reportId,
                "Alex",
                LocalDate.of(2026, 11, 20),
                roleAccess,
                null,
                "完成工作並更新測試結果");
    }

    private static ChatClient chatClientReturning(ExtractedDailyReport extracted) {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt()
                        .system(anyString())
                        .user(anyString())
                        .call()
                        .entity(ExtractedDailyReport.class))
                .thenReturn(extracted);
        return chatClient;
    }

    private static VectorIngestionService createService(
            VectorStore vectorStore, ChatClient chatClient, boolean enabled, int limit) {
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class, RETURNS_SELF);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        return new VectorIngestionService(
                vectorStore, new ObjectMapper().findAndRegisterModules(), chatClientBuilder, enabled, limit);
    }
}
