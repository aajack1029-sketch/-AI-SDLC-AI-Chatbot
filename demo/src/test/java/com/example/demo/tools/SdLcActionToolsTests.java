package com.example.demo.tools;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class SdLcActionToolsTests {

    @Test
    void createsGitHubIssueAndReturnsItsNumberAndUrl() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        server.expect(requestTo("https://api.github.com/repos/example-org/example-repo/issues"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(content().json("""
                        {
                          "title": "[交接] 完成交接文件",
                          "body": "## 交接內容\\n完成交接文件\\n\\n- 原負責人：王小明\\n- 接手代理人：@alice\\n",
                          "assignees": ["alice"]
                        }
                        """))
                .andRespond(withSuccess("""
                        {"number":42,"html_url":"https://github.com/example-org/example-repo/issues/42"}
                        """, MediaType.APPLICATION_JSON));
        SdLcActionTools tools = new SdLcActionTools(
                        restClientBuilder, " test-token ", "", "", "example-org/example-repo");

        String result = tools.createHandoverTicket(true, "王小明", "alice", "完成交接文件");

        assertThat(result)
                .contains("[#42](https://github.com/example-org/example-repo/issues/42)")
                .contains("王小明", "@alice", "完成交接文件");
        server.verify();
    }

    @Test
    void doesNotCallGitHubWhenUserHasNotConfirmed() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        SdLcActionTools tools = new SdLcActionTools(
                restClientBuilder, "test-token", "example-org", "example-repo", "");

        assertThat(tools.createHandoverTicket(false, "王小明", "alice", "完成交接文件"))
                .contains("尚未取得使用者明確確認");
        server.verify();
    }

    @Test
    void rejectsBlankIssueDetails() {
        SdLcActionTools tools = new SdLcActionTools(
                RestClient.builder(), "test-token", "example-org", "example-repo", "");

        assertThat(tools.createHandoverTicket(true, "王小明", " ", "完成交接文件"))
                .contains("請先提供接手人的 GitHub 使用者名稱");
    }

    @Test
    void rejectsMalformedCombinedRepositorySetting() {
        assertThatThrownBy(() ->
                new SdLcActionTools(RestClient.builder(), "test-token", "", "", "invalid-repository"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner/repository");
    }

    @Test
    void explainsUnauthorizedGitHubTokenWithoutLoggingOrReturningIt() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        server.expect(requestTo("https://api.github.com/repos/example-org/example-repo/issues"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer secret-test-token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        SdLcActionTools tools = new SdLcActionTools(
                restClientBuilder, "secret-test-token", "example-org", "example-repo", "");

        assertThatThrownBy(() ->
                tools.createHandoverTicket(true, "王小明", "alice", "完成交接文件"))
                .isInstanceOf(GitHubIssueCreationException.class)
                .hasMessageContaining("HTTP 401")
                .hasMessageContaining("GITHUB_TOKEN")
                .hasMessageNotContaining("secret-test-token");
        server.verify();
    }
}
