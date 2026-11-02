package com.example.demo;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
            return ResponseEntity.badRequest().body(new ChatResponse("請輸入有效的提問內容！"));
        }

        String role = resolveRole(authentication);
        if (request.filters() != null && request.filters().number() != null
                && request.filters().number() < 1) {
            return ResponseEntity.badRequest().body(new ChatResponse("GitHub 事件編號必須大於零。"));
        }
        ChatAssistantService.ChatResult result = request.filters() == null
                ? chatAssistantService.respond(request.message(), role)
                : chatAssistantService.respond(request.message(), role, request.filters());
        return ResponseEntity.ok(new ChatResponse(
                result.answer(), result.intent(), result.intents(), result.references()));
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

    // 使用 Java Record 作為 DTO (不可變、自動產生 Getter/Constructor/Equals/ToString)
    public record ChatRequest(String message, String role, GitHubEventFilters filters) {}

    public record ChatResponse(
            String answer,
            IntentCategory intent,
            List<IntentCategory> intents,
            List<ChatAssistantService.ChatSource> references) {
        public ChatResponse(String answer) {
            this(answer, null, List.of(), List.of());
        }

        public ChatResponse(String answer, IntentCategory intent) {
            this(answer, intent, intent == null ? List.of() : List.of(intent), List.of());
        }

        public ChatResponse(String answer, IntentCategory intent, List<IntentCategory> intents) {
            this(answer, intent, intents, List.of());
        }
    }
}