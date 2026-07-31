package com.insightflow.agent.investigation;

/**
 * 多轮上下文中的单条消息，供评测与线上会话共用同一焦点抽取接口。
 *
 * <p>只保存角色与正文，不含内部 id；评测从前序 user 消息抽焦点，线上从 {@link ChatMessage} 映射。</p>
 */
public record ContextTurn(String role, String content) {
}
