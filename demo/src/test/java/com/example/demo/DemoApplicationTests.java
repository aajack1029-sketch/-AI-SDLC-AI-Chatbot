package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
		"spring.ai.google.genai.api-key=test-api-key",
		"app.embedding.api-key=test-api-key",
		"app.ingestion.limit=1"
})
class DemoApplicationTests {

	@Autowired
	private EmbeddingModel embeddingModel;

	@MockitoBean(name = "knowledgeDocsVectorStore")
	private VectorStore knowledgeDocsVectorStore;

	@MockitoBean(name = "dailyReportsVectorStore")
	private VectorStore dailyReportsVectorStore;

	@MockitoBean(name = "githubEventsVectorStore")
	private VectorStore githubEventsVectorStore;

	@Test
	void contextLoadsWithCustomEmbeddingModelAndSingleReportIngestion() {
		assertThat(embeddingModel).isInstanceOf(GeminiDeveloperEmbeddingModel.class);
		verify(knowledgeDocsVectorStore).add(argThat(documents -> documents.size() == 1));
		verify(dailyReportsVectorStore).add(argThat(documents -> documents.size() == 1));
	}

}
