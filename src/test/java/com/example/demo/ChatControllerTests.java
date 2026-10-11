package com.example.demo;

import com.google.genai.errors.ClientException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerTests {

    @Test
    void returnsChineseAnswerAsUtf8Json() throws Exception {
        ChatAssistantService assistantService = mock(ChatAssistantService.class);
        when(assistantService.askAssistant("問題", "DEVELOPER")).thenReturn("這是中文回答");
        MockMvc mockMvc = createMockMvc(assistantService);

        mockMvc.perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"問題\",\"role\":\"DEVELOPER\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(result -> {
                    org.assertj.core.api.Assertions.assertThat(result.getResponse().getCharacterEncoding())
                            .isEqualTo(StandardCharsets.UTF_8.name());
                    org.assertj.core.api.Assertions.assertThat(
                                    result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                            .contains("這是中文回答");
                });
    }

    @Test
    void reportsTransientAiFailuresAsServiceUnavailable() throws Exception {
        ChatAssistantService assistantService = mock(ChatAssistantService.class);
        when(assistantService.askAssistant("問題", "DEVELOPER"))
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
        ChatAssistantService assistantService = mock(ChatAssistantService.class);
        HttpClientErrorException rateLimit = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8);
        when(assistantService.askAssistant("問題", "DEVELOPER"))
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
        ChatAssistantService assistantService = mock(ChatAssistantService.class);
        when(assistantService.askAssistant("問題", "DEVELOPER"))
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
        ChatAssistantService assistantService = mock(ChatAssistantService.class);
        when(assistantService.askAssistant("問題", "DEVELOPER"))
                .thenThrow(new IllegalStateException("unexpected failure"));

        createMockMvc(assistantService).perform(post("/api/v1/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"問題\",\"role\":\"DEVELOPER\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.answer").value("聊天請求處理失敗，請查看應用程式日誌。"));
    }

    private static MockMvc createMockMvc(ChatAssistantService assistantService) {
        return MockMvcBuilders.standaloneSetup(new ChatController(assistantService))
                .setControllerAdvice(new ChatApiExceptionHandler())
                .build();
    }
}
