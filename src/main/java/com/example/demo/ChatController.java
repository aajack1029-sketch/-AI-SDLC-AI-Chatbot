package com.example.demo;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
@CrossOrigin(origins = "*") // 允許跨域請求
public class ChatController {

    private final ChatAssistantService chatAssistantService;

    @PostMapping(value = "/query", produces = "application/json;charset=UTF-8")
    public ResponseEntity<ChatResponse> query(@RequestBody ChatRequest request) {
        if (request == null || !StringUtils.hasText(request.message())) {
            return ResponseEntity.badRequest().body(new ChatResponse("請輸入有效的提問內容！"));
        }

        String role = StringUtils.hasText(request.role()) ? request.role() : "DEVELOPER";
        String answer = chatAssistantService.askAssistant(request.message(), role);
        
        return ResponseEntity.ok(new ChatResponse(answer));
    }

    // 使用 Java Record 作為 DTO (不可變、自動產生 Getter/Constructor/Equals/ToString)
    public record ChatRequest(String message, String role) {}
    public record ChatResponse(String answer) {}
}