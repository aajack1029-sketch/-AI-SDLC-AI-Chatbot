package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatAssistantServiceTests {

    @Test
    void searchesOnlyAuthoritativeDocumentsForEnvironmentQuestions() {
        VectorStore knowledgeDocs = mock(VectorStore.class);
        VectorStore dailyReports = mock(VectorStore.class);
        VectorStore githubEvents = mock(VectorStore.class);
        ChatAssistantService service = createService(
                IntentCategory.ENVIRONMENT_DOC, knowledgeDocs, dailyReports, githubEvents);

        ChatAssistantService.ChatResult result = service.respond("測試機 IP 是多少？", "DEVELOPER");

        assertThat(result.intent()).isEqualTo(IntentCategory.ENVIRONMENT_DOC);
        verify(knowledgeDocs).similaritySearch(any(SearchRequest.class));
        verifyNoInteractions(dailyReports);
    }

    @Test
    void searchesOnlyDailyReportsForStatusQuestions() {
        VectorStore knowledgeDocs = mock(VectorStore.class);
        VectorStore dailyReports = mock(VectorStore.class);
        VectorStore githubEvents = mock(VectorStore.class);
        ChatAssistantService service = createService(
                IntentCategory.DAILY_STATUS, knowledgeDocs, dailyReports, githubEvents);

        ChatAssistantService.ChatResult result = service.respond("陳大明今天完成什麼？", "DEVELOPER");

        assertThat(result.intent()).isEqualTo(IntentCategory.DAILY_STATUS);
        verify(dailyReports).similaritySearch(any(SearchRequest.class));
        verifyNoInteractions(knowledgeDocs);
    }

    @Test
    void searchesBothSourcesWhenQuestionIncludesStatusAndCurrentEnvironment() {
        VectorStore knowledgeDocs = mock(VectorStore.class);
        VectorStore dailyReports = mock(VectorStore.class);
        VectorStore githubEvents = mock(VectorStore.class);
        when(knowledgeDocs.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("測試機目前 IP 為 192.168.1.105")));
        when(dailyReports.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("日報記錄測試機 IP 為 192.168.1.100")));
        IntentClassifier intentClassifier = mock(IntentClassifier.class);
        when(intentClassifier.classify(any()))
                .thenReturn(List.of(IntentCategory.DAILY_STATUS, IntentCategory.ENVIRONMENT_DOC));
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        ChatAssistantService service = new ChatAssistantService(
                chatClientBuilder, intentClassifier, knowledgeDocs, dailyReports, githubEvents);

        ChatAssistantService.ChatResult result = service.respond(
                "測試機目前 IP 是多少？日報有提到嗎？", "DEVELOPER");

        assertThat(result.intents())
                .containsExactly(IntentCategory.DAILY_STATUS, IntentCategory.ENVIRONMENT_DOC);
        assertThat(result.intent()).isEqualTo(IntentCategory.DAILY_STATUS);
        verify(knowledgeDocs).similaritySearch(any(SearchRequest.class));
        verify(dailyReports).similaritySearch(any(SearchRequest.class));
        org.mockito.ArgumentCaptor<String> systemPrompt = forClass(String.class);
        verify(chatClient.prompt()).system(systemPrompt.capture());
        assertThat(systemPrompt.getValue())
                .contains("權威知識庫文件", "同仁日報", "唯一標準", "192.168.1.105", "192.168.1.100");
    }

    @Test
    void skipsVectorSearchForGeneralChat() {
        VectorStore knowledgeDocs = mock(VectorStore.class);
        VectorStore dailyReports = mock(VectorStore.class);
        VectorStore githubEvents = mock(VectorStore.class);
        ChatAssistantService service = createService(
                IntentCategory.GENERAL_CHAT, knowledgeDocs, dailyReports, githubEvents);

        ChatAssistantService.ChatResult result = service.respond("你好", "DEVELOPER");

        assertThat(result.intent()).isEqualTo(IntentCategory.GENERAL_CHAT);
        verifyNoInteractions(knowledgeDocs, dailyReports, githubEvents);
    }

    @Test
    void searchesAllThreeCollectionsForGitHubSdlcQuestions() {
        VectorStore knowledgeDocs = mock(VectorStore.class);
        VectorStore dailyReports = mock(VectorStore.class);
        VectorStore githubEvents = mock(VectorStore.class);
        when(knowledgeDocs.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("權威知識：API Gateway 架構")));
        when(dailyReports.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("日報：完成登入功能測試")));
        when(githubEvents.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("abc123: 修復登入逾時問題")));
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        IntentClassifier intentClassifier = mock(IntentClassifier.class);
        when(intentClassifier.classify(any())).thenReturn(List.of(IntentCategory.GITHUB_SDLC));
        ChatAssistantService service = new ChatAssistantService(
                chatClientBuilder, intentClassifier, knowledgeDocs, dailyReports, githubEvents);

        ChatAssistantService.ChatResult result = service.respond("昨天提交了什麼程式碼修復？", "DEVELOPER");

        assertThat(result.intent()).isEqualTo(IntentCategory.GITHUB_SDLC);
        verify(knowledgeDocs).similaritySearch(any(SearchRequest.class));
        verify(dailyReports).similaritySearch(any(SearchRequest.class));
        verify(githubEvents).similaritySearch(any(SearchRequest.class));
        org.mockito.ArgumentCaptor<String> systemPrompt = forClass(String.class);
        verify(chatClient.prompt()).system(systemPrompt.capture());
        assertThat(systemPrompt.getValue()).contains(
                "權威知識庫文件", "同仁日報", "GitHub SDLC 事件", "abc123",
                "唯一標準", "Pull Request");
    }

    @Test
    void appliesMetadataFiltersToGitHubCollectionSearch() {
        VectorStore knowledgeDocs = mock(VectorStore.class);
        VectorStore dailyReports = mock(VectorStore.class);
        VectorStore githubEvents = mock(VectorStore.class);
        IntentClassifier intentClassifier = mock(IntentClassifier.class);
        when(intentClassifier.classify(any())).thenReturn(List.of(IntentCategory.GITHUB_SDLC));
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        ChatAssistantService service = new ChatAssistantService(
                chatClientBuilder, intentClassifier, knowledgeDocs, dailyReports, githubEvents);

        ChatAssistantService.ChatResult result = service.respond("登入修復", "DEVELOPER",
                new GitHubEventFilters("pull_request", "example-org/example-repo",
                        "alice", "merged", "bug", 42, "issue_pr", "8"));

        assertThat(result.answer()).isEqualTo("GitHub SDLC 事件中沒有符合所提供篩選條件的資料。");
        assertThat(result.intent()).isEqualTo(IntentCategory.GITHUB_SDLC);
        org.mockito.ArgumentCaptor<SearchRequest> searchRequest =
                forClass(SearchRequest.class);
        verify(githubEvents).similaritySearch(searchRequest.capture());
        verifyNoInteractions(knowledgeDocs, dailyReports, chatClient);
        assertThat(searchRequest.getValue().hasFilterExpression()).isTrue();
        assertThat(searchRequest.getValue().getFilterExpression().toString())
                .contains("event_type", "repository", "author", "state", "labels",
                        "number", "reference_types", "reference_ids");
    }

    @Test
    void explicitMetadataFiltersRouteTheRequestToGitHubEvents() {
        VectorStore knowledgeDocs = mock(VectorStore.class);
        VectorStore dailyReports = mock(VectorStore.class);
        VectorStore githubEvents = mock(VectorStore.class);
        ChatAssistantService service = createService(
                IntentCategory.GENERAL_CHAT, knowledgeDocs, dailyReports, githubEvents);

        ChatAssistantService.ChatResult result = service.respond(
                "查詢事件", "DEVELOPER",
                new GitHubEventFilters("issue", null, null, "open", null, 5, null, null));

        assertThat(result.intent()).isEqualTo(IntentCategory.GITHUB_SDLC);
        verify(githubEvents).similaritySearch(any(SearchRequest.class));
        verifyNoInteractions(knowledgeDocs, dailyReports);
    }

    private static ChatAssistantService createService(
            IntentCategory category,
            VectorStore knowledgeDocs,
            VectorStore dailyReports,
            VectorStore githubEvents) {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        IntentClassifier intentClassifier = mock(IntentClassifier.class);
        when(intentClassifier.classify(any())).thenReturn(List.of(category));
        return new ChatAssistantService(
                chatClientBuilder, intentClassifier, knowledgeDocs, dailyReports, githubEvents);
    }
}
