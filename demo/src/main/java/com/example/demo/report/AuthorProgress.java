package com.example.demo.report;

/** 每位作者的任務統計（SQL GROUP BY 結果）。 */
public record AuthorProgress(
        String author, long taskCount, double averageProgressPercent, long blockedCount) {
}
