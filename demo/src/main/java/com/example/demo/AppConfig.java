package com.example.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.qdrant.client.QdrantClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class AppConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean("knowledgeDocsVectorStore")
    public VectorStore knowledgeDocsVectorStore(
            QdrantClient qdrantClient,
            EmbeddingModel embeddingModel,
            @Value("${app.qdrant.knowledge-docs-collection:department_knowledge_docs_gemini_embedding_001}")
            String collectionName,
            @Value("${spring.ai.vectorstore.qdrant.initialize-schema:true}") boolean initializeSchema) {
        return QdrantVectorStore.builder(qdrantClient, embeddingModel)
                .collectionName(collectionName)
                .initializeSchema(initializeSchema)
                .build();
    }

    @Bean("dailyReportsVectorStore")
    public VectorStore dailyReportsVectorStore(
            QdrantClient qdrantClient,
            EmbeddingModel embeddingModel,
            @Value("${app.qdrant.daily-reports-collection:${QDRANT_COLLECTION:department_daily_reports_gemini_embedding_001}}")
            String collectionName,
            @Value("${spring.ai.vectorstore.qdrant.initialize-schema:true}") boolean initializeSchema) {
        return QdrantVectorStore.builder(qdrantClient, embeddingModel)
                .collectionName(collectionName)
                .initializeSchema(initializeSchema)
                .build();
    }

    @Bean("githubEventsVectorStore")
    public VectorStore githubEventsVectorStore(
            QdrantClient qdrantClient,
            EmbeddingModel embeddingModel,
            @Value("${app.qdrant.github-events-collection:github_sdlc_events}") String collectionName,
            @Value("${spring.ai.vectorstore.qdrant.initialize-schema:true}") boolean initializeSchema) {
        return QdrantVectorStore.builder(qdrantClient, embeddingModel)
                .collectionName(collectionName)
                .initializeSchema(initializeSchema)
                .build();
    }
}