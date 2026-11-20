package com.example.demo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.util.Objects;

public record ExtractedDailyReport(
        String reportId,
        String author,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate date,
        RoleAccess roleAccess,
        String ipAddress,
        String summary) {

    public static final String NO_IP_ADDRESS = "NONE";

    public ExtractedDailyReport {
        reportId = reportId == null || reportId.isBlank() ? null : reportId.trim();
        author = requireText(author, "author");
        Objects.requireNonNull(date, "date must not be null");
        Objects.requireNonNull(roleAccess, "roleAccess must not be null");
        ipAddress = ipAddress == null || ipAddress.isBlank()
                ? NO_IP_ADDRESS
                : ipAddress.trim();
        summary = requireText(summary, "summary");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
