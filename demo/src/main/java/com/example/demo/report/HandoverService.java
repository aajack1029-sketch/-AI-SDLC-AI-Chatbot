package com.example.demo.report;

import com.example.demo.report.DailyReportSqlRepository.TaskRow;
import com.example.demo.report.HandoverSummary.HandoverTask;
import com.example.demo.report.HandoverSummary.TaskStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 交接摘要：任務去重、狀態分類、依優先度或進度排序。 */
@Service
public class HandoverService {

    static final int DEFAULT_WINDOW_DAYS = 14;
    static final double DUPLICATE_SIMILARITY = 0.8;
    static final int MIN_CONTAINED_LENGTH = 6;
    private static final List<String> LEADING_STATUS_VERBS =
            List.of("已完成", "完成", "進行", "開始", "繼續", "持續", "初步", "預計");

    public enum SortBy { PRIORITY, PROGRESS }

    private final DailyReportSqlRepository repository;

    public HandoverService(DailyReportSqlRepository repository) {
        this.repository = repository;
    }

    /**
     * @param requesterRole   DEVELOPER 或 MANAGER (來自已驗證的登入角色)
     * @param requesterAuthor 登入帳號對應的作者姓名，沒有對應時為 null
     * @param requestedAuthor 想查的作者；員工留空即查自己
     */
    public HandoverSummary summarize(
            String requesterRole, String requesterAuthor, String requestedAuthor,
            LocalDate from, LocalDate to, SortBy sortBy) {
        List<String> roles = accessibleRoles(requesterRole);
        String author = resolveAuthor(requesterRole, requesterAuthor, requestedAuthor);

        LocalDate end = to != null ? to : repository.latestReportDate(author, roles).orElse(null);
        if (end == null) {
            return empty(author, from, to);
        }
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_WINDOW_DAYS - 1L);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("from 不可晚於 to");
        }

        List<TaskRow> rows = repository.findTaskRows(author, start, end, roles);
        return build(author, start, end, rows, sortBy == null ? SortBy.PRIORITY : sortBy);
    }

    static List<String> accessibleRoles(String role) {
        return switch (role == null ? "" : role.toUpperCase(Locale.ROOT)) {
            case "DEVELOPER" -> List.of("DEVELOPER", "PUBLIC");
            case "MANAGER" -> List.of("MANAGER", "DEVELOPER", "PUBLIC");
            default -> throw new AccessDeniedException("A DEVELOPER or MANAGER role is required.");
        };
    }

    private static String resolveAuthor(String role, String requesterAuthor, String requestedAuthor) {
        boolean manager = "MANAGER".equalsIgnoreCase(role);
        if (manager) {
            String author = StringUtils.hasText(requestedAuthor) ? requestedAuthor : requesterAuthor;
            if (!StringUtils.hasText(author)) {
                throw new IllegalArgumentException("請指定要查詢的作者 (author)。");
            }
            return author.trim();
        }
        if (!StringUtils.hasText(requesterAuthor)) {
            throw new AccessDeniedException("此登入帳號尚未對應到日報作者，無法查詢交接摘要。");
        }
        if (StringUtils.hasText(requestedAuthor) && !requestedAuthor.trim().equals(requesterAuthor)) {
            throw new AccessDeniedException("員工只能查詢自己的交接摘要。");
        }
        return requesterAuthor;
    }

    private static HandoverSummary empty(String author, LocalDate from, LocalDate to) {
        return new HandoverSummary(author, from, to, 0, 0, 0, zeroCounts(), List.of());
    }

    static HandoverSummary build(
            String author, LocalDate from, LocalDate to, List<TaskRow> rows, SortBy sortBy) {
        List<Cluster> clusters = new ArrayList<>();
        for (TaskRow row : rows) { // rows 依日報日期由舊到新，後面的狀態較新
            Cluster match = null;
            for (Cluster cluster : clusters) {
                if (similar(cluster.latest.taskName(), row.taskName())) {
                    match = cluster;
                    break;
                }
            }
            if (match == null) {
                clusters.add(new Cluster(row));
            } else {
                match.latest = row;
                match.count++;
            }
        }

        List<HandoverTask> tasks = new ArrayList<>();
        Map<TaskStatus, Integer> counts = zeroCounts();
        for (Cluster cluster : clusters) {
            TaskRow row = cluster.latest;
            TaskStatus status = statusOf(row);
            counts.merge(status, 1, Integer::sum);
            tasks.add(new HandoverTask(row.taskName(), row.progressPercent(), row.priority(), status,
                    row.blockerNote(), row.reportDate(), row.reportId(), cluster.count));
        }
        tasks.sort(comparator(sortBy));
        return new HandoverSummary(author, from, to, rows.size(), rows.size() - tasks.size(),
                tasks.size(), counts, tasks);
    }

    static TaskStatus statusOf(TaskRow row) {
        if (row.blocked() && row.progressPercent() < 100) {
            return TaskStatus.BLOCKED;
        }
        if (row.progressPercent() >= 100) {
            return TaskStatus.DONE;
        }
        return row.progressPercent() > 0 ? TaskStatus.IN_PROGRESS : TaskStatus.NOT_STARTED;
    }

    /** 未完成的排前面；同為未完成時，依優先度 (HIGH 先) 或進度 (低的先) 排序。 */
    private static Comparator<HandoverTask> comparator(SortBy sortBy) {
        Comparator<HandoverTask> openFirst =
                Comparator.comparing((HandoverTask task) -> task.status() == TaskStatus.DONE);
        Comparator<HandoverTask> byPriority = Comparator.comparingInt(task -> task.priority().ordinal());
        Comparator<HandoverTask> byProgress = Comparator.comparingInt(HandoverTask::progressPercent);
        Comparator<HandoverTask> blockedFirst =
                Comparator.comparing((HandoverTask task) -> task.status() != TaskStatus.BLOCKED);
        return sortBy == SortBy.PROGRESS
                ? openFirst.thenComparing(byProgress).thenComparing(byPriority)
                : openFirst.thenComparing(byPriority).thenComparing(blockedFirst).thenComparing(byProgress);
    }

    private static Map<TaskStatus, Integer> zeroCounts() {
        Map<TaskStatus, Integer> counts = new EnumMap<>(TaskStatus.class);
        for (TaskStatus status : TaskStatus.values()) {
            counts.put(status, 0);
        }
        return counts;
    }

    /**
     * 兩個任務名稱是否視為同一件事。刻意保守：交接時漏掉任務比多顯示一個重複更糟。
     * 去除空白標點與開頭的狀態動詞 (完成、進行、開始…) 後，符合下列任一條件才合併：
     * 1. 完全相同；2. 較短的名稱 (至少 6 字) 完整包含在較長的名稱內；3. 字元二連詞 Jaccard 相似度 ≥ 0.8。
     */
    static boolean similar(String a, String b) {
        String x = core(a);
        String y = core(b);
        if (x.isEmpty() || y.isEmpty()) {
            return false;
        }
        if (x.equals(y)) {
            return true;
        }
        String shorter = x.length() <= y.length() ? x : y;
        String longer = shorter == x ? y : x;
        if (shorter.length() >= MIN_CONTAINED_LENGTH && longer.contains(shorter)) {
            return true;
        }
        Set<String> gramsX = bigrams(x);
        Set<String> gramsY = bigrams(y);
        Set<String> intersection = new HashSet<>(gramsX);
        intersection.retainAll(gramsY);
        Set<String> union = new HashSet<>(gramsX);
        union.addAll(gramsY);
        return !union.isEmpty() && (double) intersection.size() / union.size() >= DUPLICATE_SIMILARITY;
    }

    private static String core(String text) {
        String result = text == null
                ? ""
                : text.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "");
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String verb : LEADING_STATUS_VERBS) {
                if (result.startsWith(verb) && result.length() > verb.length() + 1) {
                    result = result.substring(verb.length());
                    stripped = true;
                }
            }
        }
        return result;
    }

    private static Set<String> bigrams(String text) {
        Set<String> grams = new HashSet<>();
        if (text.length() < 2) {
            grams.add(text);
            return grams;
        }
        for (int index = 0; index < text.length() - 1; index++) {
            grams.add(text.substring(index, index + 2));
        }
        return grams;
    }

    private static final class Cluster {
        private TaskRow latest;
        private int count = 1;

        private Cluster(TaskRow first) {
            this.latest = first;
        }
    }
}
