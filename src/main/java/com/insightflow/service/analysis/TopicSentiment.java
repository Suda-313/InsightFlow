package com.insightflow.service.analysis;

/**
 * 一条反馈在一个既有主题上的受控情绪结果。
 *
 * <p>主题键来自既有规则目录，情绪仅能是 positive、negative、neutral 或 mixed；
 * 不携带评论片段，避免把原始反馈复制进新的分析事实。</p>
 */
public record TopicSentiment(String canonicalKey, String sentiment) {
}
