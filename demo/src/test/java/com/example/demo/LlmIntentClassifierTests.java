package com.example.demo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmIntentClassifierTests {

    @Test
    void parsesMultipleSupportedIntentLabels() {
        assertThat(LlmIntentClassifier.parseCategories("  daily_status, environment_doc\n"))
                .containsExactly(IntentCategory.DAILY_STATUS, IntentCategory.ENVIRONMENT_DOC);
        assertThat(LlmIntentClassifier.parseCategories("GITHUB_SDLC"))
                .containsExactly(IntentCategory.GITHUB_SDLC);
    }

    @Test
    void rejectsEmptyOrUnsupportedClassifierOutput() {
        assertThatThrownBy(() -> LlmIntentClassifier.parseCategories(" "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty categories");
        assertThatThrownBy(() -> LlmIntentClassifier.parseCategories("OTHER"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported or incompatible categories");
    }

    @Test
    void removesDuplicateLabelsAndRejectsMixedGeneralChat() {
        assertThat(LlmIntentClassifier.parseCategories("DAILY_STATUS, DAILY_STATUS"))
                .isEqualTo(List.of(IntentCategory.DAILY_STATUS));
        assertThatThrownBy(() -> LlmIntentClassifier.parseCategories("GENERAL_CHAT, DAILY_STATUS"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported or incompatible categories");
    }
}
