package com.example.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;

class GitHubIssueCommentsClientTests {

    @Test
    void readsIssueCommentsFromRestResponseBody() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        server.expect(requestTo(
                        "https://api.github.com/repos/example-org/example-repo/issues/8/comments?per_page=100&page=1"))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess("""
                        [{"id":202,"body":"提供重現步驟","user":{"login":"reporter"}}]
                        """, MediaType.APPLICATION_JSON));

        GitHubIssueCommentsClient client = new GitHubIssueCommentsClient(
                restClientBuilder, new ObjectMapper(), "test-token");

        JsonNode comments = client.listIssueComments("example-org", "example-repo", 8, 100);

        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).path("body").asText()).isEqualTo("提供重現步驟");
        server.verify();
    }
}
