package com.example.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
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
}
