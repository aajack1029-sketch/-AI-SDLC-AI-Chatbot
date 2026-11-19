package com.example.demo;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
            return ResponseEntity.badRequest().body(new ChatResponse(
                    ChatStatus.VALIDATION_ERROR, "請輸入有效的提問內容！"));
        }
        if (StringUtils.hasText(request.conversationId()) && !isValidConversationId(request.conversationId())) {
            return ResponseEntity.badRequest().body(new ChatResponse(
                    ChatStatus.VALIDATION_ERROR, "請提供有效的對話識別碼！"));
        }

        String role = resolveRole(authentication);
        if (request.filters() != null && request.filters().number() != null
                && request.filters().number() < 1) {
            return ResponseEntity.badRequest().body(new ChatResponse(
                    ChatStatus.VALIDATION_ERROR, "GitHub 事件編號必須大於零。"));
        }
        String userId = resolveUserId(authentication);
        String conversationId = StringUtils.hasText(request.conversationId())
                ? request.conversationId()
                : UUID.randomUUID().toString();
        ChatAssistantService.ChatResult result = request.filters() == null
                ? chatAssistantService.respondForUser(userId, conversationId, request.message(), role)
                : chatAssistantService.respondForUser(
                        userId, conversationId, request.message(), role, request.filters());
        return ResponseEntity.ok(new ChatResponse(
                result.status(), result.answer(), result.intent(), result.intents(), result.references()));
    }

    private static boolean isValidConversationId(String conversationId) {
        try {
            return UUID.fromString(conversationId).toString().equalsIgnoreCase(conversationId);
        } catch (IllegalArgumentException exception) {
            return false;
        }
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

    private String resolveUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Jwt jwt)
                || !StringUtils.hasText(jwt.getSubject())) {
            throw new AccessDeniedException("An authenticated Firebase user ID is required.");
        }
        return jwt.getSubject();
    }

    // 使用 Java Record 作為 DTO (不可變、自動產生 Getter/Constructor/Equals/ToString)
    public record ChatRequest(String message, String role, GitHubEventFilters filters, String conversationId) {}

    public record ChatResponse(
            ChatStatus status,
            String answer,
            IntentCategory intent,
            List<IntentCategory> intents,
            List<ChatAssistantService.ChatSource> references) {
        public ChatResponse {
            if (status == null) {
                throw new IllegalArgumentException("status must not be null");
            }
            if (answer == null) {
                throw new IllegalArgumentException("answer must not be null");
            }
            intents = intents == null ? List.of() : List.copyOf(intents);
            references = references == null ? List.of() : List.copyOf(references);
        }

        public ChatResponse(ChatStatus status, String answer) {
            this(status, answer, null, List.of(), List.of());
        }

        public ChatResponse(
                ChatStatus status, String answer, IntentCategory intent, List<IntentCategory> intents) {
            this(status, answer, intent, intents, List.of());
        }
    }
}