package com.example.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class VectorIngestionServiceTests {

    @Test
    void skipsIngestionWhenDisabled() {
        VectorStore knowledgeDocsVectorStore = mock(VectorStore.class);
        VectorStore dailyReportsVectorStore = mock(VectorStore.class);
        VectorIngestionService service =
                new VectorIngestionService(
                        knowledgeDocsVectorStore, dailyReportsVectorStore, new ObjectMapper(), false, 0);

        assertThatCode(() -> service.run()).doesNotThrowAnyException();
        verifyNoInteractions(knowledgeDocsVectorStore, dailyReportsVectorStore);
    }

    @Test
    void writesReportAccessLabelIntoDailyReportMetadata() throws Exception {
        VectorStore knowledgeDocsVectorStore = mock(VectorStore.class);
        VectorStore dailyReportsVectorStore = mock(VectorStore.class);
        VectorIngestionService service = new VectorIngestionService(
                knowledgeDocsVectorStore, dailyReportsVectorStore, new ObjectMapper(), true, 1);

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
                knowledgeDocsVectorStore, dailyReportsVectorStore, new ObjectMapper(), true, 0);

        service.run();

        verify(dailyReportsVectorStore, atLeastOnce()).add(argThat(documents ->
                documents.stream()
                        .filter(document -> "REP-MOCK-MANAGER-20261102-01"
                                .equals(document.getMetadata().get("report_id")))
                        .anyMatch(document -> "MANAGER"
                                .equals(document.getMetadata().get("role_access")))));
    }
}
