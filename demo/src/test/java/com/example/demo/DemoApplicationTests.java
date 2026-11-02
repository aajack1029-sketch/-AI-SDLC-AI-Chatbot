package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
		"spring.ai.google.genai.api-key=test-api-key",
		"app.embedding.api-key=test-api-key",
		"app.ingestion.limit=1",
		"JWT_ISSUER_URI=https://issuer.example.test"
})
@AutoConfigureMockMvc
class DemoApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private EmbeddingModel embeddingModel;

	@Autowired
	private VectorIngestionService vectorIngestionService;

	@Autowired
	private OAuth2TokenValidator<Jwt> firebaseAudienceValidator;

	@MockitoBean(name = "knowledgeDocsVectorStore")
	private VectorStore knowledgeDocsVectorStore;

	@MockitoBean(name = "dailyReportsVectorStore")
	private VectorStore dailyReportsVectorStore;

	@MockitoBean(name = "githubEventsVectorStore")
	private VectorStore githubEventsVectorStore;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@MockitoBean
	private ChatAssistantService chatAssistantService;

	@Test
	void contextLoadsWithCustomEmbeddingModelAndSingleReportIngestion() throws Exception {
		vectorIngestionService.run();
		assertThat(embeddingModel).isInstanceOf(GeminiDeveloperEmbeddingModel.class);
		verify(knowledgeDocsVectorStore).add(argThat(documents -> documents.size() == 1));
		verify(dailyReportsVectorStore).add(argThat(documents -> documents.size() == 1));
	}

	@Test
	void requiresAuthenticationForChatApi() throws Exception {
		mockMvc.perform(post("/api/v1/chat/query")
						.header("Origin", "http://localhost:5173")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"message\":\"問題\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("Access-Control-Expose-Headers", "WWW-Authenticate"));
	}

	@Test
	void derivesRoleFromVerifiedJwtInsteadOfRequestBody() throws Exception {
		Instant now = Instant.now();
		Jwt jwt = Jwt.withTokenValue("verified-token")
				.header("alg", "RS256")
				.subject("developer-user")
				.issuedAt(now)
				.expiresAt(now.plusSeconds(60))
				.claim("roles", List.of("DEVELOPER"))
				.build();
		when(jwtDecoder.decode("verified-token")).thenReturn(jwt);
		when(chatAssistantService.respond("問題", "DEVELOPER"))
				.thenReturn(new ChatAssistantService.ChatResult(IntentCategory.DAILY_STATUS, "回答"));

		mockMvc.perform(post("/api/v1/chat/query")
						.header("Authorization", "Bearer verified-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"message\":\"問題\",\"role\":\"MANAGER\"}"))
				.andExpect(status().isOk());

		verify(chatAssistantService).respond("問題", "DEVELOPER");
	}

	@Test
	void rejectsAuthenticatedUsersWithoutAnAllowedRole() throws Exception {
		Instant now = Instant.now();
		Jwt jwt = Jwt.withTokenValue("unprivileged-token")
				.header("alg", "RS256")
				.subject("unprivileged-user")
				.issuedAt(now)
				.expiresAt(now.plusSeconds(60))
				.claim("roles", List.of("AUDITOR"))
				.build();
		when(jwtDecoder.decode("unprivileged-token")).thenReturn(jwt);

		mockMvc.perform(post("/api/v1/chat/query")
						.header("Authorization", "Bearer unprivileged-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"message\":\"問題\"}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void acceptsOnlyIdTokensIssuedForThisFirebaseProject() {
		Instant now = Instant.now();
		Jwt validAudience = Jwt.withTokenValue("valid-audience")
				.header("alg", "RS256")
				.subject("firebase-user")
				.issuedAt(now)
				.expiresAt(now.plusSeconds(60))
				.audience(List.of("sdlc-ai-chatbot-e017d"))
				.build();
		Jwt otherAudience = Jwt.withTokenValue("other-audience")
				.header("alg", "RS256")
				.subject("other-project-user")
				.issuedAt(now)
				.expiresAt(now.plusSeconds(60))
				.audience(List.of("another-firebase-project"))
				.build();

		assertThat(firebaseAudienceValidator.validate(validAudience).hasErrors()).isFalse();
		OAuth2TokenValidatorResult rejected = firebaseAudienceValidator.validate(otherAudience);
		assertThat(rejected.hasErrors()).isTrue();
		assertThat(rejected.getErrors()).extracting("errorCode").contains("invalid_token");
	}

	@Test
	void createsFirebaseJwtDecoderFromConfiguredJwkSetWithoutIssuerDiscovery() {
		JwtDecoder decoder = new SecurityConfig().jwtDecoder(
				"https://securetoken.google.com/sdlc-ai-chatbot-e017d",
				"https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com",
				firebaseAudienceValidator);

		assertThat(decoder).isInstanceOf(NimbusJwtDecoder.class);
	}
}
