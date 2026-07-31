package com.insightflow.evaluation.rag.gold;

/** 金标多轮评测中的一条前序对话消息。 */
public record RagGoldContextTurnSnapshot(String role, String content) {
}
