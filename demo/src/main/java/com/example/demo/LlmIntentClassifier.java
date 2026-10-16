package com.example.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
public class LlmIntentClassifier implements IntentClassifier {

    private static final String CLASSIFICATION_PROMPT = """
            將使用者問題分類為下列一個或多個類別，只輸出類別名稱並以逗號分隔，不要附加說明：
            ENVIRONMENT_DOC：詢問目前有效的環境配置、IP、部署方式、操作文件或 SOP。
            DAILY_STATUS：詢問同仁、專案、工作進度、日報、歷史事件或工作統計。
            GITHUB_SDLC：詢問 GitHub Commit、Pull Request、Issue、程式碼修復或討論紀錄。
            GENERAL_CHAT：一般閒聊、常識或不需要部門內部資料的問題。
            若問題同時詢問日報/同仁工作紀錄及目前有效的環境配置，必須同時輸出 DAILY_STATUS, ENVIRONMENT_DOC。
            若問題詢問 GitHub Commit、Pull Request、Issue、程式碼修復或討論紀錄，使用 GITHUB_SDLC；若也明確詢問日報內容，可同時輸出 DAILY_STATUS, GITHUB_SDLC。
            詢問某人昨天或近期提交了什麼程式碼，仍屬於 GITHUB_SDLC，不可只因提及同仁或時間就分類為 DAILY_STATUS。
            DAILY_STATUS 只用於查詢資料庫中已記錄的歷史日報、同仁工作進度或專案紀錄。
            若使用者只是詢問一般工作建議、如何安排今日工作、提高工作效率等，且沒有要查詢資料庫中的歷史日報紀錄，
            一律分類為 GENERAL_CHAT；即使問題提到「工作」也不應分類為 DAILY_STATUS。
            即使問題提到內部環境，若只是在詢問日報中某人做過什麼，分類為 DAILY_STATUS。
            GENERAL_CHAT 只能單獨輸出，不可與其他類別並列。
            """;

    private final ChatClient chatClient;

    public LlmIntentClassifier(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public List<IntentCategory> classify(String userQuery) {
        String result = chatClient.prompt()
                .system(CLASSIFICATION_PROMPT)
                .user(userQuery)
                .call()
                .content();
        return parseCategories(result);
    }

    static List<IntentCategory> parseCategories(String result) {
        if (!StringUtils.hasText(result)) {
            throw new IllegalStateException("Intent classifier returned empty categories");
        }

        try {
            List<IntentCategory> categories = Arrays.stream(result.split(","))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .map(category -> IntentCategory.valueOf(category.toUpperCase(Locale.ROOT)))
                    .distinct()
                    .toList();

            if (categories.isEmpty()) {
                throw new IllegalStateException("Intent classifier returned empty categories");
            }
            if (categories.contains(IntentCategory.GENERAL_CHAT) && categories.size() > 1) {
                throw new IllegalArgumentException("GENERAL_CHAT cannot be combined with internal categories");
            }
            return categories;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Intent classifier returned unsupported or incompatible categories", exception);
        }
    }
}
