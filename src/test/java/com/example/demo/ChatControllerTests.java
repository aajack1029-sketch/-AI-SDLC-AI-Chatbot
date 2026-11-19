package com.example.demo;

import com.example.demo.dto.ChatResponse;
import com.google.genai.errors.ClientException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerTests {

    @Test
    void returnsChineseAnswerAsUtf8Json() throws Exception {
        ChatAssistantService assistantService = mock(ChatAssistantService.class);
        String conversationId = UUID.randomUUID().toString();
        when(assistantService.askAssistant(conversationId, "問題", "DEVELOPER"))
                .thenReturn(new ChatResponse(
                        ChatResponse.Status.SUCCESS,
                        "這是中文回答",
                        List.of("REP-20261006-03"),
                        "DAILY_REPORT"));
        MockMvc mockMvc = createMockMvc(assistantService);

        mockMvc.perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"問題\",\"role\":\"MANAGER\",\"conversationId\":\""
                                + conversationId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.sources[0]").value("REP-20261006-03"))
                .andExpect(jsonPath("$.intent").value("DAILY_REPORT"))
                .andExpect(result -> {
                    org.assertj.core.api.Assertions.assertThat(result.getResponse().getCharacterEncoding())
                            .isEqualTo(StandardCharsets.UTF_8.name());
                    org.assertj.core.api.Assertions.assertThat(
                                    result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                            .contains("這是中文回答");
                });
        verify(assistantService).askAssistant(conversationId, "問題", "DEVELOPER");
    }

    @Test
    void reportsGuardrailBlocksWithTheirOwnStatus() throws Exception {
        ChatAssistantService assistantService = mock(ChatAssistantService.class);
        String conversationId = UUID.randomUUID().toString();
        when(assistantService.askAssistant(conversationId, "惡意提問", "DEVELOPER"))
                .thenReturn(new ChatResponse(
                        ChatResponse.Status.GUARDRAIL_BLOCKED, "請求已攔截。", List.of(), "SECURITY_ALERT"));

        createMockMvc(assistantService).perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"惡意提問\",\"role\":\"DEVELOPER\",\"conversationId\":\""
                                + conversationId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GUARDRAIL_BLOCKED"))
                .andExpect(jsonPath("$.intent").value("SECURITY_ALERT"))
                .andExpect(jsonPath("$.answer").value("請求已攔截。"));
    }

    @Test
    void reportsMissingAccessibleRecordsAsRbacDenied() throws Exception {
        ChatAssistantService assistantService = mock(ChatAssistantService.class);
        String conversationId = UUID.randomUUID().toString();
        when(assistantService.askAssistant(conversationId, "查詢日報", "DEVELOPER"))
                .thenReturn(new ChatResponse(
                        ChatResponse.Status.RBAC_DENIED,
                        "沒有可存取的紀錄。",
                        List.of(),
                        "ACCESS_RESTRICTED"));

        createMockMvc(assistantService).perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"查詢日報\",\"role\":\"DEVELOPER\",\"conversationId\":\""
                                + conversationId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RBAC_DENIED"))
                .andExpect(jsonPath("$.intent").value("ACCESS_RESTRICTED"))
                .andExpect(jsonPath("$.sources").isEmpty());
    }

    @Test
    void reportsTransientAiFailuresAsServiceUnavailable() throws Exception {
        ChatAssistantService assistantService = mock(ChatAssistantService.class);
        String conversationId = UUID.randomUUID().toString();
        when(assistantService.askAssistant(conversationId, "問題", "DEVELOPER"))
                .thenThrow(new TransientAiException("provider rate limit"));

        createMockMvc(assistantService).perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"問題\",\"role\":\"DEVELOPER\",\"conversationId\":\""
                                + conversationId + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.answer").value(
                        "AI 或向量資料服務目前暫時無法使用或受到限流，請稍後再試；若持續發生，請查看應用程式日誌。"));
    }

    @Test
    void detectsRateLimitsWrappedByNonTransientAiExceptions() throws Exception {
        ChatAssistantService assistantService = mock(ChatAssistantService.class);
        String conversationId = UUID.randomUUID().toString();
        HttpClientErrorException rateLimit = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8);
        when(assistantService.askAssistant(conversationId, "問題", "DEVELOPER"))
                .thenThrow(new NonTransientAiException("provider request failed", rateLimit));

        createMockMvc(assistantService).perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"問題\",\"role\":\"DEVELOPER\",\"conversationId\":\""
                                + conversationId + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.answer").value(
                        "AI 或向量資料服務目前暫時無法使用或受到限流，請稍後再試；若持續發生，請查看應用程式日誌。"));
    }

    @Test
    void reportsGoogleGenAiQuotaFailuresAsServiceUnavailable() throws Exception {
        ChatAssistantService assistantService = mock(ChatAssistantService.class);
        String conversationId = UUID.randomUUID().toString();
        when(assistantService.askAssistant(conversationId, "問題", "DEVELOPER"))
                .thenThrow(new RuntimeException("Failed to generate content",
                        new ClientException(429, "RESOURCE_EXHAUSTED", "Daily quota exceeded")));

        createMockMvc(assistantService).perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"問題\",\"role\":\"DEVELOPER\",\"conversationId\":\""
                                + conversationId + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.answer").value(
                        "AI 或向量資料服務目前暫時無法使用或受到限流，請稍後再試；若持續發生，請查看應用程式日誌。"));
    }

    @Test
    void reportsUnexpectedFailuresWithoutReturningSuccessShapedResponses() throws Exception {
        ChatAssistantService assistantService = mock(ChatAssistantService.class);
        String conversationId = UUID.randomUUID().toString();
        when(assistantService.askAssistant(conversationId, "問題", "DEVELOPER"))
                .thenThrow(new IllegalStateException("unexpected failure"));

        createMockMvc(assistantService).perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"問題\",\"role\":\"DEVELOPER\",\"conversationId\":\""
                                + conversationId + "\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.answer").value("聊天請求處理失敗，請查看應用程式日誌。"));
    }

    @Test
    void rejectsMissingConversationId() throws Exception {
        ChatAssistantService assistantService = mock(ChatAssistantService.class);

        createMockMvc(assistantService).perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"問題\",\"role\":\"DEVELOPER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.answer").value("請提供有效的對話識別碼！"));
    }

    @Test
    void returnsValidationDtoForMalformedJson() throws Exception {
        createMockMvc(mock(ChatAssistantService.class))
                .perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.sources").isEmpty())
                .andExpect(jsonPath("$.intent").value("UNKNOWN"));
    }

    @Test
    void reportsForbiddenStatusExceptionsAsRbacDenied() {
        ResponseEntity<ChatResponse> response = new ChatApiExceptionHandler()
                .handleResponseStatus(new ResponseStatusException(HttpStatus.FORBIDDEN));

        org.assertj.core.api.Assertions.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        org.assertj.core.api.Assertions.assertThat(response.getBody().status())
                .isEqualTo(ChatResponse.Status.RBAC_DENIED);
    }

    @Test
    void deniesUnsupportedAuthenticatedRoles() throws Exception {
        ChatAssistantService assistantService = mock(ChatAssistantService.class);

        createMockMvc(assistantService, "ROLE_ADMIN").perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"管理者查詢\",\"conversationId\":\""
                                + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("RBAC_DENIED"));
        org.mockito.Mockito.verifyNoInteractions(assistantService);
    }

    private static MockMvc createMockMvc(ChatAssistantService assistantService) {
        return createMockMvc(assistantService, "ROLE_DEVELOPER");
    }

    private static MockMvc createMockMvc(ChatAssistantService assistantService, String role) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        "test-user", "test-token", List.of(new SimpleGrantedAuthority(role)));
        return MockMvcBuilders.standaloneSetup(new ChatController(assistantService))
                .defaultRequest(post("/").principal(authentication))
                .setControllerAdvice(new ChatApiExceptionHandler())
                .build();
    }
}
