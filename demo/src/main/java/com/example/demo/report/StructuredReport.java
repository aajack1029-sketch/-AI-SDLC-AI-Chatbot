package com.example.demo.report;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** 一份日報及其拆解後的任務清單。 */
public record StructuredReport(
        String reportId,
        LocalDate date,
        String author,
        String roleTitle,
        String project,
        String roleAccess,
        List<StructuredTask> tasks) {

    public StructuredReport {
        if (reportId == null || reportId.isBlank()) {
            throw new IllegalArgumentException("reportId must not be blank");
        }
        if (author == null || author.isBlank()) {
            throw new IllegalArgumentException("author must not be blank");
        }
        Objects.requireNonNull(date, "date must not be null");
        if (roleAccess == null || roleAccess.isBlank()) {
            throw new IllegalArgumentException("roleAccess must not be blank");
        }
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }
}
