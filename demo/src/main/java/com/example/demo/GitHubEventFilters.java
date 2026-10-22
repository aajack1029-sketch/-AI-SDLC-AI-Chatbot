package com.example.demo;

public record GitHubEventFilters(
        String eventType,
        String repository,
        String author,
        String state,
        String label,
        Integer number,
        String referenceType,
        String referenceId) {
}
