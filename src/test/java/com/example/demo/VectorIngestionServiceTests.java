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
        VectorStore vectorStore = mock(VectorStore.class);
        VectorIngestionService service =
                new VectorIngestionService(vectorStore, new ObjectMapper(), false, 0);

        assertThatCode(() -> service.run()).doesNotThrowAnyException();
        verifyNoInteractions(vectorStore);
    }

    @Test
    void writesReportAccessLabelIntoDailyReportMetadata() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        VectorIngestionService service =
                new VectorIngestionService(vectorStore, new ObjectMapper(), true, 1);

        service.run();

        verify(vectorStore).add(argThat(documents ->
                documents.size() == 1
                        && "DEVELOPER".equals(documents.get(0).getMetadata().get("role_access"))));
    }

    @Test
    void marksManagerPerformanceAuditReportsAsManagerOnly() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        VectorIngestionService service =
                new VectorIngestionService(vectorStore, new ObjectMapper(), true, 0);

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
}
