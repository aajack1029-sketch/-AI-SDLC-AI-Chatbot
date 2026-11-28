package com.example.demo.report;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HandoverIntentRulesTests {

    @Test
    void recognizesHandoverSummaryRequests() {
        assertThat(HandoverIntentRules.isHandoverSummaryRequest("幫我整理我的交接")).isTrue();
        assertThat(HandoverIntentRules.isHandoverSummaryRequest("幫我整理陳大明的交接")).isTrue();
        assertThat(HandoverIntentRules.isHandoverSummaryRequest("整理林小華的交接事項")).isTrue();
        assertThat(HandoverIntentRules.isHandoverSummaryRequest("給我林小華的交接摘要")).isTrue();
        assertThat(HandoverIntentRules.isHandoverSummaryRequest("列出我的未完成任務")).isTrue();
        assertThat(HandoverIntentRules.isHandoverSummaryRequest("林小華還有哪些待交接的工作？")).isTrue();
    }

    @Test
    void leavesOtherQuestionsAndExistingHandoverFlowsAlone() {
        assertThat(HandoverIntentRules.isHandoverSummaryRequest(
                "陳大明在 10/06 的工作規劃是什麼？他隔天 10/07 為什麼不在辦公室，工作交接給誰？")).isFalse();
        assertThat(HandoverIntentRules.isHandoverSummaryRequest("開立交接 Issue")).isFalse();
        assertThat(HandoverIntentRules.isHandoverSummaryRequest("幫我整理請假同事的交接")).isFalse();
        assertThat(HandoverIntentRules.isHandoverSummaryRequest("目前測試機的 IP 是多少？")).isFalse();
        assertThat(HandoverIntentRules.isHandoverSummaryRequest("最近有哪些工作日報提到測試？")).isFalse();
        assertThat(HandoverIntentRules.isHandoverSummaryRequest(null)).isFalse();
        assertThat(HandoverIntentRules.isHandoverSummaryRequest("  ")).isFalse();
    }
}
