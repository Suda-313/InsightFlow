package com.insightflow.agent.investigation;

/**
 * 一次会话当前正在讨论的调查对象。
 *
 * <p>只保存可从确定性 Tool 结果中还原的槽位；它是多轮改写的唯一素材来源，
 * 不允许写入模型自由生成的文本，否则会成为不可复核的事实源。</p>
 */
public record ChatSessionFocus(String topicKey, String timeWindow, String versionLabel) {

    /** 三个槽位均为空时表示尚无可用焦点，调用方不得覆盖会话已有焦点。 */
    public boolean isEmpty() {
        return isBlank(topicKey) && isBlank(timeWindow) && isBlank(versionLabel);
    }

    /** 供 ChatSession 读取时构造；字段可能部分为空。 */
    public static ChatSessionFocus of(String topicKey, String timeWindow, String versionLabel) {
        return new ChatSessionFocus(topicKey, timeWindow, versionLabel);
    }

    /** 显式空焦点，避免调用方传 null。 */
    public static ChatSessionFocus empty() {
        return new ChatSessionFocus(null, null, null);
    }

    /**
     * 合并两个焦点：{@code incoming} 中非空槽位覆盖 {@code base}，用于证据与用户消息分源抽取。
     */
    public ChatSessionFocus merge(ChatSessionFocus incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return this;
        }
        return new ChatSessionFocus(
                firstNonBlank(incoming.topicKey, topicKey),
                firstNonBlank(incoming.timeWindow, timeWindow),
                firstNonBlank(incoming.versionLabel, versionLabel));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return isBlank(preferred) ? fallback : preferred.trim();
    }
}
