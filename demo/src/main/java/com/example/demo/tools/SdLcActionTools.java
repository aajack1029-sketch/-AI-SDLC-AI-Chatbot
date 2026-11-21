package com.example.demo.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SdLcActionTools {

    private static final Logger logger = LoggerFactory.getLogger(SdLcActionTools.class);
    private static final String GITHUB_API_URL = "https://api.github.com";
    private static final String ISSUE_TITLE_PREFIX = "[交接] ";
    private static final int MAX_ISSUE_TITLE_LENGTH = 256;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String token;
    private final String repositoryOwner;
    private final String repositoryName;

    public SdLcActionTools(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${GITHUB_TOKEN:${app.github.token:}}") String token,
            @Value("${GITHUB_REPOSITORY_OWNER:${app.github.repository.owner:}}") String repositoryOwner,
            @Value("${GITHUB_REPOSITORY_NAME:${app.github.repository.name:}}") String repositoryName,
            @Value("${GITHUB_REPO:${app.github.repo:}}") String repository) {
        this.restClient = restClientBuilder.clone().baseUrl(GITHUB_API_URL).build();
        this.objectMapper = objectMapper;
        this.token = token == null ? "" : token.trim();
        this.repositoryOwner = repositoryPart(repositoryOwner, repository, 0);
        this.repositoryName = repositoryPart(repositoryName, repository, 1);
    }

    @Tool(description = """
            在已設定的 GitHub repository 建立交接 Issue。
            只有使用者明確確認要建立交接工單後才可呼叫；不得把詢問、建議或未確認的要求視為確認。
            assignee 必須是 GitHub 使用者名稱（login）。
            """)
    public String createHandoverTicket(
            @ToolParam(description = "使用者是否已明確確認建立交接工單；未確認時必須為 false")
            boolean userConfirmed,
            @ToolParam(description = "原負責人姓名") String originalOwner,
            @ToolParam(description = "接手代理人的 GitHub 使用者名稱（login）") String assignee,
            @ToolParam(description = "交接任務內容摘要") String taskSummary) {
        if (!userConfirmed) {
            logger.warn("Skipped handover issue creation because user confirmation was not provided");
            return jsonResult("not_created", "尚未取得使用者明確確認。", null, null, null);
        }
        if (!StringUtils.hasText(originalOwner)) {
            return jsonResult("not_created", "請先提供原負責人姓名。", null, null, null);
        }
        if (!StringUtils.hasText(assignee)) {
            return jsonResult("not_created", "請先提供接手人的 GitHub 使用者名稱。", null, null, null);
        }
        if (!StringUtils.hasText(taskSummary)) {
            return jsonResult("not_created", "請先提供交接任務摘要。", null, null, null);
        }

        String owner = originalOwner.trim();
        String githubAssignee = assignee.trim();
        String summary = taskSummary.trim();
        String title = ISSUE_TITLE_PREFIX + summary;
        if (title.length() > MAX_ISSUE_TITLE_LENGTH) {
            return jsonResult("not_created", "摘要過長，請縮短至 GitHub Issue 標題限制內。", null, null, null);
        }

        if (!StringUtils.hasText(token)) {
            return jsonResult("error", "GITHUB_TOKEN is required to create GitHub Issues.", null, null, null);
        }
        if (!StringUtils.hasText(repositoryOwner) || !StringUtils.hasText(repositoryName)) {
            return jsonResult("error",
                    "Set GITHUB_REPO or both GITHUB_REPOSITORY_OWNER and GITHUB_REPOSITORY_NAME.",
                    null, null, null);
        }

        Map<String, Object> requestBody = Map.of(
                "title", title,
                "body", """
                        ## 交接內容
                        %s

                        - 原負責人：%s
                        - 接手代理人：@%s
                        """.formatted(summary, owner, githubAssignee),
                "assignees", List.of(githubAssignee));

        GitHubIssue issue;
        try {
            issue = restClient.post()
                    .uri("/repos/{owner}/{repo}/issues", repositoryOwner, repositoryName)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("Authorization", "Bearer " + token)
                    .body(requestBody)
                    .retrieve()
                    .body(GitHubIssue.class);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            String diagnosis = switch (status) {
                case 401 -> "GitHub did not accept the configured token; check GITHUB_TOKEN and restart the backend.";
                case 403 -> "GitHub denied Issue creation; check Issues write access and organization SSO authorization.";
                case 404 -> "The repository was not found or is not accessible to this token.";
                case 422 -> "GitHub rejected the Issue data; check the assignee and repository assignment policy.";
                default -> "Check the GitHub API response and repository settings.";
            };
            logger.warn("GitHub Issue creation failed with HTTP {} for {}/{}",
                    status, repositoryOwner, repositoryName);
            return jsonResult("error",
                    "GitHub Issues API returned HTTP %d. %s".formatted(status, diagnosis),
                    null, null, status);
        } catch (RestClientException exception) {
            logger.warn("GitHub Issue creation request failed for {}/{} ({})",
                    repositoryOwner, repositoryName, exception.getClass().getSimpleName());
            return jsonResult("error",
                    "Unable to reach GitHub Issues API; check network connectivity and try again.",
                    null, null, null);
        }
        if (issue == null || issue.number() < 1) {
            return jsonResult("error", "GitHub Issues API returned an invalid created Issue response.",
                    null, null, null);
        }

        String issueUrl = "https://github.com/%s/%s/issues/%d"
                .formatted(repositoryOwner, repositoryName, issue.number());
        logger.info("Created handover issue #{} in {}/{}", issue.number(), repositoryOwner, repositoryName);
        return jsonResult("created",
                "已成功建立交接工單 [#%d](%s)。原負責人：%s，接手人：@%s，任務內容：%s"
                        .formatted(issue.number(), issueUrl, owner, githubAssignee, summary),
                issue.number(), issueUrl, null);
    }

    private String jsonResult(
            String status, String message, Integer issueNumber, String issueUrl, Integer httpStatus) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status);
        response.put("message", message);
        if (issueNumber != null) {
            response.put("issueNumber", issueNumber);
            response.put("issueUrl", issueUrl);
        }
        if (httpStatus != null) {
            response.put("httpStatus", httpStatus);
        }
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            logger.error("Failed to serialize GitHub tool response", exception);
            return "{\"status\":\"error\",\"message\":\"Unable to serialize GitHub tool response.\"}";
        }
    }

    private static String repositoryPart(String configuredPart, String repository, int partIndex) {
        if (StringUtils.hasText(configuredPart)) {
            return configuredPart.trim();
        }
        if (!StringUtils.hasText(repository)) {
            return "";
        }
        String[] parts = repository.trim().split("/", -1);
        if (parts.length != 2 || !StringUtils.hasText(parts[0]) || !StringUtils.hasText(parts[1])) {
            throw new IllegalArgumentException("GITHUB_REPO must use the owner/repository format");
        }
        return parts[partIndex].trim();
    }

    private record GitHubIssue(int number) {
    }
}
