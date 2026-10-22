package com.example.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitHubEventSyncServiceTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void syncsCommitsPullRequestsIssuesAndCommentsIdempotently() throws Exception {
        GitHubMcpClient mcpClient = mock(GitHubMcpClient.class);
        GitHubIssueCommentsClient issueCommentsClient = mock(GitHubIssueCommentsClient.class);
        VectorStore vectorStore = mock(VectorStore.class);
        when(mcpClient.listTools()).thenReturn(objectMapper.readTree("""
                [
                  {"name":"list_commits","inputSchema":{"properties":{"owner":{},"repo":{},"perPage":{}}}},
                  {"name":"list_pull_requests","inputSchema":{"properties":{"owner":{},"repo":{},"perPage":{},"page":{},"state":{},"sort":{},"direction":{}}}},
                  {"name":"list_issues","inputSchema":{"properties":{"owner":{},"repo":{},"perPage":{},"page":{},"state":{},"sort":{},"direction":{}}}},
                  {"name":"get_pull_request_comments","inputSchema":{"properties":{"owner":{},"repo":{},"pull_number":{},"perPage":{},"page":{}}}}
                ]
                """));
        when(mcpClient.callTool(anyString(), any(JsonNode.class))).thenAnswer(invocation -> {
            String tool = invocation.getArgument(0);
            return switch (tool) {
                case "list_commits" -> result("""
                        [{"sha":"abc123","commit":{"message":"修復登入錯誤","author":{"name":"Dev","date":"2026-10-14T10:00:00Z"}}}]
                        """);
                case "list_pull_requests" -> result("""
                        [{"number":7,"title":"改善登入","body":"更新驗證流程。Closes #8","state":"open","updated_at":"2026-10-14T10:00:00Z","html_url":"https://github.com/example-org/example-repo/pull/7","head":{"sha":"def456"},"labels":[{"name":"bug"}],"user":{"login":"dev"}}]
                        """);
                case "list_issues" -> result("""
                        [
                          {"number":8,"title":"登入逾時","body":"偶發逾時問題","state":"open","updated_at":"2026-10-14T10:00:00Z","html_url":"https://github.com/example-org/example-repo/issues/8","labels":[{"name":"triage"}],"user":{"login":"reporter"}},
                          {"number":7,"pull_request":{"url":"https://example.test/pr/7"}}
                        ]
                        """);
                case "get_pull_request_comments" -> result("""
                        [{"id":101,"body":"已補上測試","created_at":"2026-10-14T11:00:00Z","html_url":"https://github.com/example-org/example-repo/pull/7#issuecomment-101","user":{"login":"reviewer"}}]
                        """);
                default -> throw new AssertionError("Unexpected tool: " + tool);
            };
        });
        when(issueCommentsClient.listIssueComments("example-org", "example-repo", 8, 100))
                .thenReturn(objectMapper.readTree("""
                        [{"id":202,"body":"提供重現步驟","created_at":"2026-10-14T12:00:00Z","html_url":"https://github.com/example-org/example-repo/issues/8#issuecomment-202","user":{"login":"reporter"}}]
                        """));
        GitHubEventSyncService service = new GitHubEventSyncService(
                mcpClient, issueCommentsClient, objectMapper, vectorStore,
                "example-org", "example-repo", 100);

        GitHubEventSyncService.SyncResult first = service.syncNow();
        GitHubEventSyncService.SyncResult second = service.syncNow();

        assertThat(first.eventCount()).isEqualTo(5);
        assertThat(second.eventCount()).isEqualTo(5);
        assertThat(service.status().lastFailure()).isNull();
        assertThat(service.status().lastSuccessfulSync()).isEqualTo(second.completedAt());

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<Document>> documents =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(vectorStore, org.mockito.Mockito.times(2)).add(documents.capture());
        List<Document> firstSync = documents.getAllValues().get(0);
        List<Document> secondSync = documents.getAllValues().get(1);
        assertThat(firstSync).extracting(Document::getText)
                .anyMatch(text -> text.contains("修復登入錯誤"))
                .anyMatch(text -> text.contains("更新驗證流程"))
                .anyMatch(text -> text.contains("已補上測試"))
                .anyMatch(text -> text.contains("偶發逾時問題"))
                .anyMatch(text -> text.contains("提供重現步驟"));
        assertThat(firstSync).extracting(Document::getId)
                .containsExactlyElementsOf(secondSync.stream().map(Document::getId).toList());

        Document pullRequest = firstSync.stream()
                .filter(document -> "pull_request".equals(document.getMetadata().get("event_type")))
                .findFirst()
                .orElseThrow();
        assertThat(pullRequest.getMetadata())
                .containsEntry("repository", "example-org/example-repo")
                .containsEntry("author", "dev")
                .containsEntry("state", "open")
                .containsEntry("number", 7);
        assertThat(pullRequest.getMetadata().get("labels")).isEqualTo(List.of("bug"));
        assertThat(pullRequest.getMetadata().get("reference_types"))
                .isEqualTo(List.of("source_url", "pr_commit", "issue_pr"));
        assertThat(pullRequest.getMetadata().get("reference_ids"))
                .isEqualTo(List.of("7", "def456", "8"));
        assertThat(pullRequest.getMetadata().get("number")).isEqualTo(7);
        assertThat(pullRequest.getMetadata().get("references").toString())
                .contains("\"targetType\":\"issue\"", "\"targetId\":\"8\"");
        assertThat(pullRequest.getText())
                .contains("https://github.com/example-org/example-repo/pull/7",
                        "https://github.com/example-org/example-repo/commit/def456",
                        "https://github.com/example-org/example-repo/issues/8");
        assertThat(firstSync).anySatisfy(document -> {
            assertThat(document.getMetadata())
                    .containsEntry("event_type", "issue")
                    .containsEntry("number", 8);
            assertThat(document.getText()).contains("登入逾時", "偶發逾時問題");
        });
        verify(issueCommentsClient, org.mockito.Mockito.times(2))
                .listIssueComments("example-org", "example-repo", 8, 100);
    }

    @Test
    void rejectsMissingPullRequestCommentToolInsteadOfSilentlySkippingComments() throws Exception {
        GitHubMcpClient mcpClient = mock(GitHubMcpClient.class);
        GitHubIssueCommentsClient issueCommentsClient = mock(GitHubIssueCommentsClient.class);
        when(mcpClient.listTools()).thenReturn(objectMapper.readTree("""
                [{"name":"list_commits"},{"name":"list_pull_requests"},{"name":"list_issues"}]
                """));
        GitHubEventSyncService service = new GitHubEventSyncService(
                mcpClient, issueCommentsClient, objectMapper, mock(VectorStore.class),
                "example-org", "example-repo", 100);

        org.assertj.core.api.Assertions.assertThatThrownBy(service::syncNow)
                .isInstanceOf(GitHubMcpException.class)
                .hasMessageContaining("Pull Request comments");
    }

    private JsonNode result(String json) {
        var result = objectMapper.createObjectNode();
        result.putArray("content").addObject()
                .put("type", "text")
                .put("text", json);
        return result;
    }
}
