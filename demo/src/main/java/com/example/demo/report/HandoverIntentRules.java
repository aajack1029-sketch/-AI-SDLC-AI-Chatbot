package com.example.demo.report;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 交接摘要的明確關鍵字規則。
 *
 * <p>實測 Gemini 的意圖分類會把「幫我整理我的交接」判成一般聊天、把「整理陳大明的交接」判成日報查詢，
 * 結果走到向量檢索而不是 SQL 統計。這類句型很固定，所以先用規則判斷，命中就直接走交接摘要；
 * 沒命中才交給 LLM 分類。
 */
public final class HandoverIntentRules {

    private static final Pattern HANDOVER_REQUEST = Pattern.compile(
            "(整理|產生|生成|列出|查看|查詢|看一下|看看|給我|提供).{0,10}(交接|待辦|未完成)"
                    + "|交接.{0,2}(摘要|清單|事項|總覽)"
                    + "|待交接"
                    + "|(未完成|待辦).{0,2}(任務|事項|工作|清單)");

    // 請假交接、開立/建立交接 Issue 屬於既有的工單流程，不是統計摘要。
    private static final Pattern EXISTING_HANDOVER_FLOWS = Pattern.compile(
            "請假|休假|開立|建立|issue|工單", Pattern.CASE_INSENSITIVE);

    private HandoverIntentRules() {
    }

    public static boolean isHandoverSummaryRequest(String userQuery) {
        if (userQuery == null || userQuery.isBlank()) {
            return false;
        }
        String normalized = userQuery.toLowerCase(Locale.ROOT);
        return HANDOVER_REQUEST.matcher(normalized).find()
                && !EXISTING_HANDOVER_FLOWS.matcher(normalized).find();
    }
}
