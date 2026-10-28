package com.example.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class ChatAssistantService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public ChatAssistantService(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
    }

    public String askAssistant(String userQuery, String userRole) {
        // 1. 從 Qdrant 檢索最相關的 3 筆 Context
        // 開發除錯階段建議將 similarityThreshold 調低（如 0.2），避免因為分數差距導致搜不到資料
        SearchRequest searchRequest = SearchRequest.builder()
                .query(userQuery)
                .topK(3)
                .similarityThreshold(0.2) 
                .build();

        List<Document> similarDocuments = vectorStore.similaritySearch(searchRequest);

        if (similarDocuments == null || similarDocuments.isEmpty()) {
            return "在部門資料庫中未找到與您的問題相關的日報或文件紀錄。";
        }

        // 安全抽取文件文字內容
        String context = similarDocuments.stream()
                .map(document -> document != null ? document.getText() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n---\n"));

        // 2. 構建 System Prompt，融入 userRole 與內部日報 Context
        String systemPrompt = """
            你是一個專業的科技公司「雲端與應用開發部門 AI 助理」。
            當前提問的使用者身份/角色為：【%s】。
            請根據下方提供之【內部日報 Context】精確回答使用者的問題。
            
            規則：
            1. 僅根據 Context 內有的事實回答，禁止捏造未記載之內容。
            2. 若 Context 無法回答，請明確回覆「資料庫中查無相關紀錄」。
            3. 輸出風格需簡明、專業。
            
            【內部日報 Context】：
            %s
            """.formatted(userRole != null ? userRole : "一般員工", context);

        return chatClient.prompt()
                .system(systemPrompt)
                .user(userQuery)
                .call()
                .content();
    }
}