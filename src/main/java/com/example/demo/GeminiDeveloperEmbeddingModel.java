package com.example.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class GeminiDeveloperEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSIONS = 3072;
    private static final String API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/{model}:embedContent";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;

    public GeminiDeveloperEmbeddingModel(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${app.embedding.api-key}") String apiKey,
            @Value("${app.embedding.model:gemini-embedding-001}") String model) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        List<Embedding> embeddings = new ArrayList<>(texts.size());

        for (int i = 0; i < texts.size(); i++) {
            embeddings.add(new Embedding(embedText(texts.get(i)), i));
        }

        EmbeddingResponseMetadata metadata = new EmbeddingResponseMetadata();
        metadata.setModel(model);
        return new EmbeddingResponse(embeddings, metadata);
    }

    @Override
    public float[] embed(Document document) {
        return embedText(getEmbeddingContent(document));
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    private float[] embedText(String text) {
        Map<String, Object> body = Map.of(
                "model", "models/" + model,
                "content", Map.of("parts", List.of(Map.of("text", text))));

        String responseBody = restClient.post()
                .uri(API_URL, model)
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-goog-api-key", apiKey)
                .body(body)
                .retrieve()
                .body(String.class);

        JsonNode response;
        try {
            response = responseBody == null ? null : objectMapper.readTree(responseBody);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Gemini embedding response was not valid JSON", exception);
        }

        JsonNode values = response == null ? null : response.path("embedding").path("values");
        if (values == null || !values.isArray() || values.size() != DIMENSIONS) {
            throw new IllegalStateException(
                    "Gemini embedding response must contain exactly " + DIMENSIONS + " values");
        }

        float[] vector = new float[DIMENSIONS];
        for (int i = 0; i < DIMENSIONS; i++) {
            JsonNode value = values.get(i);
            if (!value.isNumber()) {
                throw new IllegalStateException("Gemini embedding response contains a non-numeric value");
            }
            vector[i] = value.floatValue();
        }
        return vector;
    }
}
