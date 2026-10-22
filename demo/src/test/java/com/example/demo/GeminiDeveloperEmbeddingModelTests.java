package com.example.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class GeminiDeveloperEmbeddingModelTests {

    @Test
    void mapsGeminiDeveloperApiSingleEmbeddingResponse() throws Exception {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();

        String responseBody = new ObjectMapper().writeValueAsString(Map.of(
                "embedding", Map.of("values", java.util.Collections.nCopies(3072, 0.25))));
        server.expect(requestTo(
                        "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent"))
                .andExpect(method(POST))
                .andExpect(header("x-goog-api-key", "test-api-key"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        GeminiDeveloperEmbeddingModel model = new GeminiDeveloperEmbeddingModel(
                restClientBuilder, new ObjectMapper(), "test-api-key", "gemini-embedding-001");

        float[] embedding = model.embed(
                        List.of(new Document("test input")),
                        null,
                        documents -> List.of(documents))
                .get(0);

        assertThat(embedding).hasSize(3072);
        assertThat(embedding[0]).isEqualTo(0.25f);
        server.verify();
    }
}
