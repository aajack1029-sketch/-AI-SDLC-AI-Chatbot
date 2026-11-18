package com.example.demo;

import com.google.genai.errors.ClientException;
import org.mockito.Answers;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerTests {

    @Test
    void forwardsConversationIdToAssistantService() throws Exception {
        ChatAssistantService assistantService = mockChatAssistantService();
        String conversationId = UUID.randomUUID().toString();
        doReturn(new ChatAssistantService.ChatResult(
                        IntentCategory.GENERAL_CHAT, "你上一個問題是「哪個 PR 修復登入問題？」。"))
                .when(assistantService)
                .respondForUser(
                        "firebase-test-uid", conversationId, "我上個問題問甚麼", "DEVELOPER");

        createMockMvc(assistantService).perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"我上個問題問甚麼","conversationId":"%s"}
                                """.formatted(conversationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer")
                        .value("你上一個問題是「哪個 PR 修復登入問題？」。"));

        verify(assistantService).respondForUser(
                "firebase-test-uid", conversationId, "我上個問題問甚麼", "DEVELOPER");
    }

    @Test
    void rejectsMalformedConversationIds() throws Exception {
        ChatAssistantService assistantService = mockChatAssistantService();

        createMockMvc(assistantService).perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"問題","conversationId":"not-a-uuid"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.answer").value("請提供有效的對話識別碼！"));

        verifyNoInteractions(assistantService);
    }

    @Test
    void allowsCorsRequestsFromViteDevelopmentServer() throws Exception {
        ChatAssistantService assistantService = mockChatAssistantService();
        when(assistantService.respond("問題", "DEVELOPER"))
                .thenReturn(new ChatAssistantService.ChatResult(IntentCategory.DAILY_STATUS, "回答"));

        createMockMvc(assistantService).perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .content("{\"message\":\"問題\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
    }

    @Test
    void rejectsCorsRequestsFromOtherOrigins() throws Exception {
        ChatAssistantService assistantService = mockChatAssistantService();

        createMockMvc(assistantService).perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ORIGIN, "https://example.com")
                        .content("{\"message\":\"問題\"}"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void returnsChineseAnswerAsUtf8Json() throws Exception {
        ChatAssistantService assistantService = mockChatAssistantService();
        when(assistantService.respond("問題", "DEVELOPER"))
                .thenReturn(new ChatAssistantService.ChatResult(IntentCategory.DAILY_STATUS, "這是中文回答"));
        MockMvc mockMvc = createMockMvc(assistantService);

        mockMvc.perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"問題\",\"role\":\"DEVELOPER\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.intent").value("DAILY_STATUS"))
                .andExpect(jsonPath("$.intents[0]").value("DAILY_STATUS"))
                .andExpect(result -> {
                    org.assertj.core.api.Assertions.assertThat(result.getResponse().getCharacterEncoding())
                            .isEqualTo(StandardCharsets.UTF_8.name());
                    org.assertj.core.api.Assertions.assertThat(
                                    result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                            .contains("這是中文回答");
                });
    }

    @Test
    void returnsAllClassifiedIntentsAlongsideThePrimaryIntent() throws Exception {
        ChatAssistantService assistantService = mockChatAssistantService();
        when(assistantService.respond("測試機 IP 與日報", "DEVELOPER"))
                .thenReturn(new ChatAssistantService.ChatResult(
                        List.of(IntentCategory.DAILY_STATUS, IntentCategory.ENVIRONMENT_DOC), "回答"));

        createMockMvc(assistantService).perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"測試機 IP 與日報\",\"role\":\"DEVELOPER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("DAILY_STATUS"))
                .andExpect(jsonPath("$.intents[0]").value("DAILY_STATUS"))
                .andExpect(jsonPath("$.intents[1]").value("ENVIRONMENT_DOC"));
    }

    @Test
    void returnsSourceReferencesAlongsideTheAnswer() throws Exception {
        ChatAssistantService assistantService = mockChatAssistantService();
        when(assistantService.respond("Issue 內容", "DEVELOPER"))
                .thenReturn(new ChatAssistantService.ChatResult(
                        List.of(IntentCategory.GITHUB_SDLC),
                        "回答",
                        List.of(new ChatAssistantService.ChatSource(
                                "GitHub SDLC 事件", "issue #12",
                                "https://github.com/example/repo/issues/12"))));

        createMockMvc(assistantService).perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Issue 內容\",\"role\":\"DEVELOPER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.references[0].label").value("GitHub SDLC 事件"))
                .andExpect(jsonPath("$.references[0].reference").value("issue #12"))
                .andExpect(jsonPath("$.references[0].url")
                        .value("https://github.com/example/repo/issues/12"));
    }

    @Test
    void forwardsGitHubMetadataFilters() throws Exception {
        ChatAssistantService assistantService = mockChatAssistantService();
        GitHubEventFilters filters = new GitHubEventFilters(
                "pull_request", "example-org/example-repo", "alice", "merged", "bug", 42,
                "issue_pr", "8");
        when(assistantService.respond("登入修復", "DEVELOPER", filters))
                .thenReturn(new ChatAssistantService.ChatResult(IntentCategory.GITHUB_SDLC, "回答"));

        createMockMvc(assistantService).perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"登入修復","role":"DEVELOPER","filters":{
                                  "eventType":"pull_request","repository":"example-org/example-repo",
                                  "author":"alice","state":"merged","label":"bug","number":42,
                                  "referenceType":"issue_pr","referenceId":"8"
                                }}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("GITHUB_SDLC"));

        verify(assistantService).respond("登入修復", "DEVELOPER", filters);
    }

    @Test
    void ignoresUntrustedRoleSuppliedInRequestBody() throws Exception {
        ChatAssistantService assistantService = mockChatAssistantService();
        when(assistantService.respond("問題", "DEVELOPER"))
                .thenReturn(new ChatAssistantService.ChatResult(IntentCategory.DAILY_STATUS, "回答"));

        createMockMvc(assistantService).perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"問題\",\"role\":\"MANAGER\"}"))
                .andExpect(status().isOk());

        verify(assistantService).respond("問題", "DEVELOPER");
    }

    @Test
    void rejectsNonPositiveGithubEventNumbers() throws Exception {
        ChatAssistantService assistantService = mockChatAssistantService();
        createMockMvc(assistantService).perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"登入修復","filters":{"number":0}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.answer").value("GitHub 事件編號必須大於零。"));
    }

    @Test
    void deniesAdminRoleFromChatApi() throws Exception {
        ChatAssistantService assistantService = mockChatAssistantService();

        createMockMvc(assistantService, "ROLE_ADMIN")
                .perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"管理者查詢\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.answer").value("此帳號沒有聊天 API 權限。"));

        verifyNoInteractions(assistantService);
    }

    @Test
    void reportsTransientAiFailuresAsServiceUnavailable() throws Exception {
        ChatAssistantService assistantService = mockChatAssistantService();
        when(assistantService.respond("問題", "DEVELOPER"))
                .thenThrow(new TransientAiException("provider rate limit"));

        createMockMvc(assistantService).perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"問題\",\"role\":\"DEVELOPER\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.answer").value(
                        "AI 或向量資料服務目前暫時無法使用或受到限流，請稍後再試；若持續發生，請查看應用程式日誌。"));
    }

    @Test
    void detectsRateLimitsWrappedByNonTransientAiExceptions() throws Exception {
        ChatAssistantService assistantService = mockChatAssistantService();
        HttpClientErrorException rateLimit = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8);
        when(assistantService.respond("問題", "DEVELOPER"))
                .thenThrow(new NonTransientAiException("provider request failed", rateLimit));

        createMockMvc(assistantService).perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"問題\",\"role\":\"DEVELOPER\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.answer").value(
                        "AI 或向量資料服務目前暫時無法使用或受到限流，請稍後再試；若持續發生，請查看應用程式日誌。"));
    }

    @Test
    void reportsGoogleGenAiQuotaFailuresAsServiceUnavailable() throws Exception {
        ChatAssistantService assistantService = mockChatAssistantService();
        when(assistantService.respond("問題", "DEVELOPER"))
                .thenThrow(new RuntimeException("Failed to generate content",
                        new ClientException(429, "RESOURCE_EXHAUSTED", "Daily quota exceeded")));

        createMockMvc(assistantService).perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"問題\",\"role\":\"DEVELOPER\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.answer").value(
                        "AI 或向量資料服務目前暫時無法使用或受到限流，請稍後再試；若持續發生，請查看應用程式日誌。"));
    }

    @Test
    void reportsUnexpectedFailuresWithoutReturningSuccessShapedResponses() throws Exception {
        ChatAssistantService assistantService = mockChatAssistantService();
        when(assistantService.respond("問題", "DEVELOPER"))
                .thenThrow(new IllegalStateException("unexpected failure"));

        createMockMvc(assistantService).perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"問題\",\"role\":\"DEVELOPER\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.answer").value("聊天請求處理失敗，請查看應用程式日誌。"));
    }

    @Test
    void reportsGitHubMcpFailuresAsBadGateway() throws Exception {
        ChatAssistantService assistantService = mockChatAssistantService();
        when(assistantService.respond("提交紀錄", "DEVELOPER"))
                .thenThrow(new GitHubMcpException("MCP server unavailable"));

        createMockMvc(assistantService).perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"提交紀錄\",\"role\":\"DEVELOPER\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.answer").value(
                        "AI、向量資料或 GitHub MCP 服務回覆錯誤，請查看應用程式日誌。"));
    }

    private static MockMvc createMockMvc(ChatAssistantService assistantService) {
        return createMockMvc(assistantService, "ROLE_DEVELOPER");
    }

    private static MockMvc createMockMvc(ChatAssistantService assistantService, String role) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject("firebase-test-uid")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
        return MockMvcBuilders.standaloneSetup(new ChatController(assistantService))
                .defaultRequest(post("/").principal(new JwtAuthenticationToken(
                        jwt, List.of(new SimpleGrantedAuthority(role)))))
                .setControllerAdvice(new ChatApiExceptionHandler())
                .build();
    }

    private static ChatAssistantService mockChatAssistantService() {
        return mock(ChatAssistantService.class, invocation -> {
            if (invocation.getMethod().getName().equals("respondForUser")) {
                ChatAssistantService assistantService =
                        (ChatAssistantService) invocation.getMock();
                if (invocation.getArguments().length == 4) {
                    return assistantService.respond(
                            (String) invocation.getArgument(2),
                            (String) invocation.getArgument(3));
                }
                return assistantService.respond(
                        (String) invocation.getArgument(2),
                        (String) invocation.getArgument(3),
                        (GitHubEventFilters) invocation.getArgument(4));
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }
}
