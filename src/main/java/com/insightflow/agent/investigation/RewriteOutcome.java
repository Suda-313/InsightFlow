package com.insightflow.agent.investigation;

/**
 * 规则改写的结果与审计元数据。
 *
 * @param triggered false 时 {@code rewritten} 与 {@code original} 必须是同一个字符串实例
 * @param reason    触发原因或未触发原因，只写入 Trace，不进入 Prompt
 */
public record RewriteOutcome(String original, String rewritten, boolean triggered, String reason) {
}
