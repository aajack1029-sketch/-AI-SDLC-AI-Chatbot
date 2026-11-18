package com.example.demo;

import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/chat")
@CrossOrigin(origins = "*") // 允許跨域請求
public class ChatController {

    private final ChatAssistantService chatAssistantService;
    public ChatController(ChatAssistantService chatAssistantService) {
        this.chatAssistantService = chatAssistantService;
    }
    @PostMapping(value = "/query", produces = "application/json;charset=UTF-8")
    public ResponseEntity<ChatResponse> query(@RequestBody ChatRequest request) {
        if (request == null || !StringUtils.hasText(request.message())) {
            return ResponseEntity.badRequest().body(new ChatResponse("請輸入有效的提問內容！"));
        }
        if (!isValidConversationId(request.conversationId())) {
            return ResponseEntity.badRequest().body(new ChatResponse("請提供有效的對話識別碼！"));
        }

        String role = StringUtils.hasText(request.role()) ? request.role() : "DEVELOPER";
        String answer = chatAssistantService.askAssistant(request.conversationId(), request.message(), role);
        
        return ResponseEntity.ok(new ChatResponse(answer));
    }

    private static boolean isValidConversationId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return false;
        }
        try {
            return UUID.fromString(conversationId).toString().equalsIgnoreCase(conversationId);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    // 使用 Java Record 作為 DTO (不可變、自動產生 Getter/Constructor/Equals/ToString)
    public record ChatRequest(String message, String role, String conversationId) {}
    public record ChatResponse(String answer) {}
}