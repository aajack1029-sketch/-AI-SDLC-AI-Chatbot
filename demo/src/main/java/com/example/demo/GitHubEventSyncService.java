package com.example.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.github.sync.enabled", havingValue = "true")
public class GitHubEventSyncService {

    private static final String LIST_COMMITS = "list_commits";
    private static final String LIST_PULL_REQUESTS = "list_pull_requests";
    private static final String LIST_ISSUES = "list_issues";
    private static final Pattern CLOSING_ISSUE_PATTERN =
            Pattern.compile("(?i)\\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\\s+#(\\d+)\\b");
    private static final Pattern NUMBER_MENTION_PATTERN = Pattern.compile("#(\\d+)\\b");
    private static final List<String> PULL_REQUEST_COMMENT_TOOLS =
            List.of("get_pull_request_comments", "list_pull_request_comments",
                    "get_pull_request_review_comments", "list_pull_request_review_comments",
                    "get_issue_comments", "list_issue_comments");
    private static final List<String> ISSUE_COMMENT_TOOLS =
            List.of("get_issue_comments", "list_issue_comments");

    private final GitHubMcpClient mcpClient;
    private final GitHubIssueCommentsClient issueCommentsClient;
    private final ObjectMapper objectMapper;
    private final VectorStore githubEventsVectorStore;
    private final String repositoryOwner;
    private final String repositoryName;
    private final int pageSize;
    private volatile Instant lastSuccessfulSync;
    private volatile String lastFailure;

    public GitHubEventSyncService(
            GitHubMcpClient mcpClient,
            GitHubIssueCommentsClient issueCommentsClient,
            ObjectMapper objectMapper,
            @Qualifier("githubEventsVectorStore") VectorStore githubEventsVectorStore,
            @Value("${app.github.repository.owner:}") String repositoryOwner,
            @Value("${app.github.repository.name:}") String repositoryName,
            @Value("${app.github.sync.page-size:100}") int pageSize) {
        this.mcpClient = mcpClient;
        this.issueCommentsClient = issueCommentsClient;
        this.objectMapper = objectMapper;
        this.githubEventsVectorStore = githubEventsVectorStore;
        this.repositoryOwner = repositoryOwner;
        this.repositoryName = repositoryName;
        this.pageSize = pageSize;
    }

    @Scheduled(
            initialDelayString = "${app.github.sync.initial-delay-ms:15000}",
            fixedDelayString = "${app.github.sync.interval-ms:900000}")
    public void syncOnSchedule() {
        try {
            syncNow();
        } catch (RuntimeException exception) {
            log.error("Scheduled GitHub SDLC event sync failed", exception);
        }
    }

    public synchronized SyncResult syncNow() {
        if (!StringUtils.hasText(repositoryOwner) || !StringUtils.hasText(repositoryName)) {
            throw new GitHubMcpException(
                    "GITHUB_REPOSITORY_OWNER and GITHUB_REPOSITORY_NAME are required for GitHub event sync");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalStateException("app.github.sync.page-size must be between 1 and 100");
        }

        try {
            Map<String, JsonNode> tools = availableTools();
            JsonNode pullRequestCommentTool = findTool(tools, PULL_REQUEST_COMMENT_TOOLS);
            JsonNode issueCommentTool = findTool(tools, ISSUE_COMMENT_TOOLS);
            requireTool(tools, LIST_COMMITS);
            requireTool(tools, LIST_PULL_REQUESTS);
            requireTool(tools, LIST_ISSUES);
            if (pullRequestCommentTool == null) {
                throw new GitHubMcpException("GitHub MCP server does not provide a Pull Request comments tool");
            }
            if (issueCommentTool == null) {
                log.warn("GitHub MCP has no Issue comments tool; using GitHub REST API fallback");
            }

            LinkedHashMap<String, Document> events = new LinkedHashMap<>();
            addCommits(events, tools.get(LIST_COMMITS));
            addPullRequests(events, tools.get(LIST_PULL_REQUESTS), pullRequestCommentTool);
            addIssues(events, tools.get(LIST_ISSUES), issueCommentTool);

            if (!events.isEmpty()) {
                githubEventsVectorStore.add(List.copyOf(events.values()));
            }
            lastSuccessfulSync = Instant.now();
            lastFailure = null;
            SyncResult result = new SyncResult(events.size(), lastSuccessfulSync);
            log.info("Synchronized GitHub SDLC events for {}/{} into Qdrant: "
                            + "commits={}, pullRequests={}, pullRequestComments={}, issues={}, "
                            + "issueComments={}, total={}",
                    repositoryOwner, repositoryName,
                    countEvents(events, "commit"),
                    countEvents(events, "pull_request"),
                    countEvents(events, "pull_request_comment"),
                    countEvents(events, "issue"),
                    countEvents(events, "issue_comment"),
                    result.eventCount());
            return result;
        } catch (RuntimeException exception) {
            lastFailure = exception.getMessage();
            throw exception;
        }
    }

    public SyncStatus status() {
        return new SyncStatus(lastSuccessfulSync, lastFailure);
    }

    private Map<String, JsonNode> availableTools() {
        JsonNode tools = mcpClient.listTools();
        if (!tools.isArray()) {
            throw new GitHubMcpException("GitHub MCP server returned an invalid tools/list response");
        }
        Map<String, JsonNode> available = new LinkedHashMap<>();
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText();
            if (StringUtils.hasText(name)) {
                available.put(name, tool);
            }
        }
        return available;
    }

    private void requireTool(Map<String, JsonNode> tools, String toolName) {
        if (!tools.containsKey(toolName)) {
            throw new GitHubMcpException("Configured GitHub MCP server does not provide the "
                    + toolName + " tool required for event sync");
        }
    }

    private JsonNode findTool(Map<String, JsonNode> tools, List<String> candidates) {
        return candidates.stream().map(tools::get).filter(tool -> tool != null).findFirst().orElse(null);
    }

    private void addCommits(Map<String, Document> events, JsonNode tool) {
        JsonNode result = call(tool, Map.of(
                "owner", repositoryOwner, "repo", repositoryName, "perPage", pageSize));
        for (JsonNode commit : records(result, "commits")) {
            String sha = requiredText(commit, "sha");
            JsonNode commitData = commit.path("commit");
            String message = firstText(commitData.path("message"), commit.path("message"));
            String date = firstText(commitData.path("author").path("date"),
                    commitData.path("committer").path("date"));
            String author = firstText(commit.path("author").path("login"),
                    commitData.path("author").path("name"), commitData.path("author").path("email"));
            String sourceUrl = firstValue(commit.path("html_url").asText(""),
                    commit.path("url").asText(""), repositoryUrl() + "/commit/" + sha);
            List<GitHubReference> references = List.of(
                    new GitHubReference("source_url", "commit", sha, sourceUrl));
            addEvent(events, "commit:" + sha, eventText("Commit", sha, message, author, date),
                    "commit", sha, date, author, "committed", List.of(), "", references);
        }
    }

    private void addPullRequests(
            Map<String, Document> events, JsonNode listTool, JsonNode commentsTool) {
        JsonNode result = call(listTool, Map.of(
                "owner", repositoryOwner, "repo", repositoryName, "perPage", pageSize,
                "page", 1, "state", "all", "sort", "updated", "direction", "desc"));
        for (JsonNode pullRequest : records(result, "pull_requests")) {
            String number = requiredText(pullRequest, "number");
            String title = firstText(pullRequest.path("title"));
            String body = firstText(pullRequest.path("body"));
            String date = firstText(pullRequest.path("updated_at"), pullRequest.path("created_at"));
            String author = pullRequest.path("user").path("login").asText("");
            String state = pullRequest.hasNonNull("merged_at")
                    ? "merged" : pullRequest.path("state").asText("");
            String sourceUrl = firstValue(pullRequest.path("html_url").asText(""),
                    repositoryUrl() + "/pull/" + number);
            List<GitHubReference> references = new ArrayList<>();
            references.add(new GitHubReference("source_url", "pull_request", number, sourceUrl));
            String headSha = pullRequest.path("head").path("sha").asText("");
            if (StringUtils.hasText(headSha)) {
                String headRepository = firstValue(
                        pullRequest.path("head").path("repo").path("full_name").asText(""),
                        repositoryOwner + "/" + repositoryName);
                references.add(new GitHubReference("pr_commit", "commit", headSha,
                        "https://github.com/" + headRepository + "/commit/" + headSha));
            }
            references.addAll(issueReferences(body, true));
            List<String> labels = labels(pullRequest.path("labels"));
            String text = eventText("Pull Request #" + number, title, body, author, state, date,
                    "Labels: " + String.join(", ", labels));
            addEvent(events, "pull_request:" + number, text, "pull_request", number, date,
                    author, state, labels, number, references);

            JsonNode commentResult = call(commentsTool, commentArguments(commentsTool, number, true));
            addComments(events, records(commentResult, "comments"),
                    "pull_request_comment", "Pull Request #" + number + " comment", number,
                    "pull_request", sourceUrl);
        }
    }

    private void addIssues(Map<String, Document> events, JsonNode listTool, JsonNode commentsTool) {
        JsonNode result = call(listTool, Map.of(
                "owner", repositoryOwner, "repo", repositoryName, "perPage", pageSize,
                "page", 1, "state", "all", "sort", "updated", "direction", "desc"));
        for (JsonNode issue : records(result, "issues")) {
            if (issue.has("pull_request")) {
                continue;
            }
            String number = requiredText(issue, "number");
            String title = firstText(issue.path("title"));
            String body = firstText(issue.path("body"));
            String date = firstText(issue.path("updated_at"), issue.path("created_at"));
            String author = issue.path("user").path("login").asText("");
            String state = issue.path("state").asText("");
            String sourceUrl = firstValue(issue.path("html_url").asText(""),
                    repositoryUrl() + "/issues/" + number);
            List<String> labels = labels(issue.path("labels"));
            List<GitHubReference> references = new ArrayList<>();
            references.add(new GitHubReference("source_url", "issue", number, sourceUrl));
            references.addAll(issueReferences(body, false));
            String text = eventText("Issue #" + number, title, body, author, state, date,
                    "Labels: " + String.join(", ", labels));
            addEvent(events, "issue:" + number, text, "issue", number, date,
                    author, state, labels, number, references);

            List<JsonNode> comments = commentsTool == null
                    ? unwrapRecords(issueCommentsClient.listIssueComments(repositoryOwner, repositoryName,
                            Integer.parseInt(number), pageSize), "comments")
                    : records(call(commentsTool, commentArguments(commentsTool, number, false)), "comments");
            addComments(events, comments, "issue_comment",
                    "Issue #" + number + " comment", number, "issue", sourceUrl);
        }
    }

    private void addComments(
            Map<String, Document> events,
            List<JsonNode> comments,
            String eventType,
            String label,
            String parentNumber,
            String parentType,
            String parentUrl) {
        for (JsonNode comment : comments) {
            String id = requiredText(comment, "id");
            String body = firstText(comment.path("body"), comment.path("text"));
            String author = comment.path("user").path("login").asText("");
            String date = firstText(comment.path("updated_at"), comment.path("created_at"));
            String sourceUrl = firstValue(comment.path("html_url").asText(""),
                    comment.path("url").asText(""), parentUrl);
            List<GitHubReference> references = new ArrayList<>();
            references.add(new GitHubReference("source_url", eventType, id, sourceUrl));
            references.add(new GitHubReference("parent", parentType, parentNumber, parentUrl));
            references.addAll(issueReferences(body, false));
            addEvent(events, eventType + ":" + id, eventText(label + " #" + id,
                            body, author, date, sourceUrl),
                    eventType, id, date, author, "", List.of(), parentNumber, references);
        }
    }

    private JsonNode call(JsonNode tool, Map<String, ?> values) {
        ObjectNode arguments = objectMapper.createObjectNode();
        Set<String> properties = new LinkedHashSet<>();
        JsonNode schemaProperties = tool.path("inputSchema").path("properties");
        if (schemaProperties.isObject()) {
            schemaProperties.fieldNames().forEachRemaining(properties::add);
        }
        values.forEach((key, value) -> {
            if (properties.isEmpty() || properties.contains(key)) {
                arguments.set(key, objectMapper.valueToTree(value));
            }
        });
        return mcpClient.callTool(tool.path("name").asText(), arguments);
    }

    private Map<String, Object> commentArguments(JsonNode tool, String number, boolean pullRequest) {
        JsonNode properties = tool.path("inputSchema").path("properties");
        String numberProperty = pullRequest && properties.has("pull_number")
                ? "pull_number" : "issue_number";
        return Map.of(
                "owner", repositoryOwner,
                "repo", repositoryName,
                numberProperty, Integer.parseInt(number),
                "perPage", pageSize,
                "page", 1);
    }

    private List<JsonNode> records(JsonNode result, String... collectionNames) {
        JsonNode structuredContent = result.get("structuredContent");
        if (structuredContent != null) {
            return unwrapRecords(structuredContent, collectionNames);
        }

        JsonNode content = result.path("content");
        if (!content.isArray()) {
            throw new GitHubMcpException("GitHub MCP tool returned no readable content");
        }
        List<JsonNode> records = new ArrayList<>();
        boolean parsedContent = false;
        for (JsonNode item : content) {
            if (!"text".equals(item.path("type").asText()) || !item.hasNonNull("text")) {
                continue;
            }
            String text = item.path("text").asText();
            if (text.isBlank()) {
                continue;
            }
            if (text.equalsIgnoreCase("no comments found")) {
                parsedContent = true;
                continue;
            }
            try {
                records.addAll(unwrapRecords(objectMapper.readTree(text), collectionNames));
                parsedContent = true;
            } catch (IOException exception) {
                throw new GitHubMcpException(
                        "GitHub MCP returned non-JSON event data; refusing to silently omit it", exception);
            }
        }
        if (content.isEmpty()) {
            return List.of();
        }
        if (!parsedContent) {
            throw new GitHubMcpException("GitHub MCP tool returned no parseable event data");
        }
        return records;
    }

    private List<JsonNode> unwrapRecords(JsonNode node, String... collectionNames) {
        if (node.isArray()) {
            List<JsonNode> records = new ArrayList<>();
            node.forEach(records::add);
            return records;
        }
        if (!node.isObject()) {
            throw new GitHubMcpException("GitHub MCP returned an invalid event data shape");
        }
        for (String name : collectionNames) {
            JsonNode collection = node.get(name);
            if (collection != null) {
                return unwrapRecords(collection, collectionNames);
            }
        }
        for (String name : List.of("items", "data", "results")) {
            JsonNode collection = node.get(name);
            if (collection != null) {
                return unwrapRecords(collection, collectionNames);
            }
        }
        return List.of(node);
    }

    private void addEvent(
            Map<String, Document> events,
            String key,
            String text,
            String eventType,
            String externalId,
            String eventDate,
            String author,
            String state,
            List<String> labels,
            String number,
            List<GitHubReference> references) {
        String stableKey = repositoryOwner + "/" + repositoryName + ":" + key;
        String documentId = UUID.nameUUIDFromBytes(stableKey.getBytes(StandardCharsets.UTF_8)).toString();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("repository", repositoryOwner + "/" + repositoryName);
        metadata.put("event_type", eventType);
        metadata.put("external_id", externalId);
        metadata.put("event_date", eventDate);
        metadata.put("author", author);
        metadata.put("state", state);
        metadata.put("labels", labels);
        if (StringUtils.hasText(number)) {
            metadata.put("number", Integer.parseInt(number));
        }
        metadata.put("references", objectMapper.valueToTree(references).toString());
        metadata.put("reference_types", references.stream().map(GitHubReference::type).distinct().toList());
        metadata.put("reference_ids", references.stream().map(GitHubReference::targetId).distinct().toList());
        metadata.put("reference_urls", references.stream().map(GitHubReference::url).distinct().toList());
        String referenceText = references.stream()
                .map(reference -> reference.type() + " -> " + reference.targetType()
                        + " #" + reference.targetId() + " " + reference.url())
                .distinct()
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        String searchableText = StringUtils.hasText(referenceText)
                ? text + "\nReferences:\n" + referenceText
                : text;
        events.put(key, new Document(documentId, searchableText, metadata));
    }

    private String eventText(String... fields) {
        StringBuilder text = new StringBuilder("Repository: ")
                .append(repositoryOwner).append('/').append(repositoryName);
        for (String field : fields) {
            if (StringUtils.hasText(field)) {
                text.append('\n').append(field);
            }
        }
        return text.toString();
    }

    private List<String> labels(JsonNode labels) {
        if (!labels.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        labels.forEach(label -> {
            String name = label.isTextual() ? label.asText() : label.path("name").asText("");
            if (StringUtils.hasText(name)) {
                values.add(name);
            }
        });
        return List.copyOf(values);
    }

    private List<GitHubReference> issueReferences(String text, boolean parseClosingIssueRelations) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<GitHubReference> references = new ArrayList<>();
        Set<String> closingIssueNumbers = new HashSet<>();
        if (parseClosingIssueRelations) {
            Matcher closingMatcher = CLOSING_ISSUE_PATTERN.matcher(text);
            while (closingMatcher.find()) {
                String issueNumber = closingMatcher.group(1);
                closingIssueNumbers.add(issueNumber);
                references.add(new GitHubReference("issue_pr", "issue", issueNumber,
                        repositoryUrl() + "/issues/" + issueNumber));
            }
        }
        Matcher mentionMatcher = NUMBER_MENTION_PATTERN.matcher(text);
        while (mentionMatcher.find()) {
            String mentionedNumber = mentionMatcher.group(1);
            if (!closingIssueNumbers.contains(mentionedNumber)) {
                references.add(new GitHubReference("mentions", "issue_or_pull_request", mentionedNumber,
                        repositoryUrl() + "/issues/" + mentionedNumber));
            }
        }
        return references.stream().distinct().toList();
    }

    private String repositoryUrl() {
        return "https://github.com/" + repositoryOwner + "/" + repositoryName;
    }

    private long countEvents(Map<String, Document> events, String eventType) {
        return events.values().stream()
                .filter(event -> eventType.equals(event.getMetadata().get("event_type")))
                .count();
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (!StringUtils.hasText(value)) {
            throw new GitHubMcpException("GitHub MCP event is missing required field: " + field);
        }
        return value;
    }

    private String firstText(JsonNode... values) {
        for (JsonNode value : values) {
            String text = value.asText("");
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return "";
    }

    private String firstValue(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    public record SyncResult(int eventCount, Instant completedAt) {}

    public record SyncStatus(Instant lastSuccessfulSync, String lastFailure) {}

    private record GitHubReference(String type, String targetType, String targetId, String url) {}
}
