package com.example.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class GitHubSdlcService {

    private final GitHubMcpClient mcpClient;
    private final ObjectMapper objectMapper;
    private final String repositoryOwner;
    private final String repositoryName;

    public GitHubSdlcService(
            GitHubMcpClient mcpClient,
            ObjectMapper objectMapper,
            @Value("${app.github.repository.owner:}") String repositoryOwner,
            @Value("${app.github.repository.name:}") String repositoryName) {
        this.mcpClient = mcpClient;
        this.objectMapper = objectMapper;
        this.repositoryOwner = repositoryOwner;
        this.repositoryName = repositoryName;
    }

    public String getRecentCommits() {
        if (!StringUtils.hasText(repositoryOwner) || !StringUtils.hasText(repositoryName)) {
            throw new GitHubMcpException(
                    "GITHUB_REPOSITORY_OWNER and GITHUB_REPOSITORY_NAME are required for GitHub commit queries");
        }

        JsonNode tools = mcpClient.listTools();
        boolean supportsListCommits = false;
        for (JsonNode tool : tools) {
            if ("list_commits".equals(tool.path("name").asText())) {
                supportsListCommits = true;
                break;
            }
        }
        if (!supportsListCommits) {
            throw new GitHubMcpException("Configured GitHub MCP server does not provide the list_commits tool");
        }

        JsonNode arguments = objectMapper.createObjectNode()
                .put("owner", repositoryOwner)
                .put("repo", repositoryName)
                .put("perPage", 100);
        JsonNode result = mcpClient.callTool("list_commits", arguments);
        JsonNode content = result.path("content");
        if (!content.isArray() || content.isEmpty()) {
            throw new GitHubMcpException("GitHub MCP list_commits returned no commit data");
        }

        StringBuilder commits = new StringBuilder();
        for (JsonNode item : content) {
            if ("text".equals(item.path("type").asText()) && item.hasNonNull("text")) {
                if (!commits.isEmpty()) {
                    commits.append('\n');
                }
                commits.append(item.path("text").asText());
            }
        }
        if (commits.isEmpty()) {
            throw new GitHubMcpException("GitHub MCP list_commits returned no readable commit data");
        }
        return commits.toString();
    }
}
