package com.example.demo.report;

import com.example.demo.ChatStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/handover")
public class HandoverController {

    private final HandoverService handoverService;
    private final AuthorResolver authorResolver;

    public HandoverController(HandoverService handoverService, AuthorResolver authorResolver) {
        this.handoverService = handoverService;
        this.authorResolver = authorResolver;
    }

    @GetMapping(value = "/summary", produces = "application/json;charset=UTF-8")
    public ResponseEntity<?> summary(
            @RequestParam(required = false) String author,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "priority") String sort,
            Authentication authentication) {
        String role = resolveRole(authentication);
        HandoverService.SortBy sortBy;
        try {
            sortBy = HandoverService.SortBy.valueOf(sort.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest()
                    .body(new ErrorBody(ChatStatus.VALIDATION_ERROR, "sort 只能是 priority 或 progress。"));
        }
        try {
            HandoverSummary summary = handoverService.summarize(
                    role, authorResolver.resolve(authentication).orElse(null), author, from, to, sortBy);
            return ResponseEntity.ok(summary);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest()
                    .body(new ErrorBody(ChatStatus.VALIDATION_ERROR, exception.getMessage()));
        }
    }

    private static String resolveRole(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("An authenticated user is required.");
        }
        boolean manager = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).anyMatch("ROLE_MANAGER"::equals);
        if (manager) {
            return "MANAGER";
        }
        boolean developer = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).anyMatch("ROLE_DEVELOPER"::equals);
        if (developer) {
            return "DEVELOPER";
        }
        throw new AccessDeniedException("A DEVELOPER or MANAGER role is required.");
    }

    public record ErrorBody(ChatStatus status, String error) {}
}
