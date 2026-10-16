package com.example.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;

@Service
public class GitHubIssueCommentsClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String token;

    public GitHubIssueCommentsClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${app.github.token:}") String token) {
        this.restClient = restClientBuilder.clone().baseUrl("https://api.github.com").build();
        this.objectMapper = objectMapper;
        this.token = token;
    }

    public JsonNode listIssueComments(String owner, String repository, int issueNumber, int pageSize) {
        if (!StringUtils.hasText(token)) {
            throw new GitHubMcpException(
                    "GITHUB_PERSONAL_ACCESS_TOKEN is required to retrieve Issue comments");
        }

        String responseBody = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/{owner}/{repo}/issues/{issue_number}/comments")
                        .queryParam("per_page", pageSize)
                        .queryParam("page", 1)
                        .build(owner, repository, issueNumber))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(String.class);
        JsonNode comments;
        try {
            comments = responseBody == null ? null : objectMapper.readTree(responseBody);
        } catch (IOException exception) {
            throw new GitHubMcpException("GitHub REST API returned invalid Issue comments JSON", exception);
        }
        if (comments == null || !comments.isArray()) {
            throw new GitHubMcpException("GitHub REST API returned an invalid Issue comments response");
        }
        return comments;
    }
}
