package com.example.demo;

import com.example.demo.dto.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
		"spring.ai.google.genai.api-key=test-api-key",
		"app.embedding.api-key=test-api-key",
		"app.ingestion.limit=1",
		"spring.security.oauth2.resourceserver.jwt.issuer-uri=https://securetoken.google.com/test-firebase-project",
		"app.security.jwt.audience=test-firebase-project"
})
@AutoConfigureMockMvc
class DemoApplicationTests {

	@Autowired
	private EmbeddingModel embeddingModel;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private VectorStore vectorStore;

	@MockitoBean
	private ChatAssistantService chatAssistantService;

	@Test
	void loadsContextAndUsesTheVerifiedTokenRoleForChatRequests() throws Exception {
		assertThat(embeddingModel).isInstanceOf(GeminiDeveloperEmbeddingModel.class);

		when(chatAssistantService.askAssistant(
				"00000000-0000-0000-0000-000000000001", "問題", "DEVELOPER"))
				.thenReturn(new ChatResponse(ChatResponse.Status.SUCCESS, "回答"));
		mockMvc.perform(post("/api/v1/chat/query")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"message":"問題","role":"MANAGER",
								 "conversationId":"00000000-0000-0000-0000-000000000001"}
								""")
						.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DEVELOPER"))))
				.andExpect(status().isOk());

		verify(chatAssistantService).askAssistant(
				"00000000-0000-0000-0000-000000000001", "問題", "DEVELOPER");
	}

	@Test
	void requiresAnAuthenticatedChatUserAndReturnsTheSharedRbacDto() throws Exception {
		mockMvc.perform(post("/api/v1/chat/query")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"message":"問題",
								 "conversationId":"00000000-0000-0000-0000-000000000001"}
								"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.status").value("RBAC_DENIED"));
	}

	@Test
	void rejectsAdminRoleAtTheSecurityFilter() throws Exception {
		mockMvc.perform(post("/api/v1/chat/query")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"message":"管理者查詢",
								 "conversationId":"00000000-0000-0000-0000-000000000001"}
								""")
						.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.status").value("RBAC_DENIED"));
	}

}
