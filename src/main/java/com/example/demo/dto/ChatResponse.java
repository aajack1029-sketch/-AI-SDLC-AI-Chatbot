package com.example.demo.dto;

import java.util.List;
import java.util.Objects;

public record ChatResponse(
        Status status,
        String answer,
        List<String> sources,
        String intent) {

    public ChatResponse {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(answer, "answer must not be null");
        sources = sources == null ? List.of() : List.copyOf(sources);
        intent = intent == null ? "UNKNOWN" : intent;
    }

    public ChatResponse(Status status, String answer) {
        this(status, answer, List.of(), "UNKNOWN");
    }

    public enum Status {
        SUCCESS,
        GUARDRAIL_BLOCKED,
        RBAC_DENIED,
        VALIDATION_ERROR,
        ERROR
    }
}
