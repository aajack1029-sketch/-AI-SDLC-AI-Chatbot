package com.example.demo;

import com.example.demo.dto.ExtractedDailyReport;
import com.example.demo.dto.RoleAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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

    private static ChatClient.Builder chatClientBuilder() {
        return chatClientBuilder(mock(ChatClient.class));
    }

    private static ChatClient.Builder chatClientBuilder(ChatClient chatClient) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(chatClient);
        return builder;
    }
}
