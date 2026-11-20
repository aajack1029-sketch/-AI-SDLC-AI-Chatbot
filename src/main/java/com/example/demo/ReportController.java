package com.example.demo;

import com.example.demo.dto.ExtractedDailyReport;
import com.example.demo.dto.RoleAccess;
import com.example.demo.dto.ChatResponse;
import com.example.demo.security.InputSanitizer;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
@CrossOrigin(origins = "${app.cors.allowed-origin:http://localhost:5173}")
public class ReportController {

    private final VectorIngestionService ingestionService;
    private final InputSanitizer inputSanitizer;

    public ReportController(
            VectorIngestionService ingestionService, InputSanitizer inputSanitizer) {
        this.ingestionService = ingestionService;
        this.inputSanitizer = inputSanitizer;
    }

    @PostMapping(
            value = "/ingest",
            consumes = "application/json",
            produces = "application/json;charset=UTF-8")
    public ResponseEntity<?> ingestReport(
            @RequestBody RawReportRequest request, Authentication authentication) {
        RoleAccess authenticatedRole = resolveRole(authentication);
        if (request == null || !StringUtils.hasText(request.rawText())) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(ChatResponse.Status.VALIDATION_ERROR, "請輸入有效的日報內容。"));
        }
        if (inputSanitizer.containsPromptInjection(request.rawText())) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(
                            ChatResponse.Status.GUARDRAIL_BLOCKED,
                            "日報內容包含不允許的指令，系統已拒絕寫入。"));
        }

        ExtractedDailyReport savedReport =
                ingestionService.ingestRawReport(request.rawText(), authenticatedRole);
        return ResponseEntity.ok(new IngestResponse("INGESTED", savedReport));
    }

    private RoleAccess resolveRole(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("An authenticated user is required.");
        }

        boolean isManager = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_MANAGER"::equals);
        if (isManager) {
            return RoleAccess.MANAGER;
        }
        boolean isDeveloper = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_DEVELOPER"::equals);
        if (isDeveloper) {
            return RoleAccess.DEVELOPER;
        }
        throw new AccessDeniedException("A DEVELOPER or MANAGER role is required.");
    }

    public record RawReportRequest(String rawText) {}

    public record IngestResponse(String status, ExtractedDailyReport data) {}

    public record ErrorResponse(ChatResponse.Status status, String error) {}
}
