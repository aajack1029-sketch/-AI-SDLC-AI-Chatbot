package com.example.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitHubSdlcServiceTests {

    @Test
    void callsListCommitsForConfiguredRepository() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        GitHubMcpClient mcpClient = mock(GitHubMcpClient.class);
        when(mcpClient.listTools()).thenReturn(objectMapper.readTree("""
                [{"name":"list_commits"}]
                """));
        when(mcpClient.callTool(eq("list_commits"), any(JsonNode.class)))
                .thenReturn(objectMapper.readTree("""
                        {"content":[{"type":"text","text":"abc123: 修復登入逾時問題"}]}
                        """));
        GitHubSdlcService service = new GitHubSdlcService(
                mcpClient, objectMapper, "example-org", "example-repo");

        assertThat(service.getRecentCommits()).contains("abc123", "修復登入逾時問題");

        org.mockito.ArgumentCaptor<JsonNode> arguments = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(mcpClient).callTool(eq("list_commits"), arguments.capture());
        assertThat(arguments.getValue().path("owner").asText()).isEqualTo("example-org");
        assertThat(arguments.getValue().path("repo").asText()).isEqualTo("example-repo");
        assertThat(arguments.getValue().path("perPage").asInt()).isEqualTo(100);
    }
}
