package com.example.demo;

import com.example.demo.dto.ChatResponse;
import com.example.demo.dto.ChatResponse.Status;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/chat")
@CrossOrigin(origins = "${app.cors.allowed-origin:http://localhost:5173}")
public class ChatController {

    private final ChatAssistantService chatAssistantService;
    public ChatController(ChatAssistantService chatAssistantService) {
        this.chatAssistantService = chatAssistantService;
    }
    @PostMapping(value = "/query", produces = "application/json;charset=UTF-8")
    public ResponseEntity<ChatResponse> query(
            @RequestBody ChatRequest request, Authentication authentication) {
        if (request == null || !StringUtils.hasText(request.message())) {
            return ResponseEntity.badRequest()
                    .body(new ChatResponse(Status.VALIDATION_ERROR, "請輸入有效的提問內容！"));
        }
        if (!isValidConversationId(request.conversationId())) {
            return ResponseEntity.badRequest()
                    .body(new ChatResponse(Status.VALIDATION_ERROR, "請提供有效的對話識別碼！"));
        }

        String role = resolveRole(authentication);
        ChatResponse response =
                chatAssistantService.askAssistant(request.conversationId(), request.message(), role);
        return ResponseEntity.ok(response);
    }

    private String resolveRole(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("An authenticated user is required.");
        }
        boolean isManager = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_MANAGER"::equals);
        if (isManager) {
            return "MANAGER";
        }
        boolean isDeveloper = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_DEVELOPER"::equals);
        if (isDeveloper) {
            return "DEVELOPER";
        }
        throw new AccessDeniedException("A DEVELOPER or MANAGER role is required.");
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
    // role is retained for request compatibility; authorization uses the verified token.
    public record ChatRequest(String message, String role, String conversationId) {}
}