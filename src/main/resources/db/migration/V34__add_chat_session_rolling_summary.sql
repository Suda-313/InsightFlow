-- 超长会话滚动摘要：12 条窗口之外的更早轮次压缩为确定性文本，降低 Prompt token。
ALTER TABLE chat_session
    ADD COLUMN rolling_summary TEXT;

COMMENT ON COLUMN chat_session.rolling_summary IS
    '超出短期记忆窗口的更早对话确定性摘要；不存模型推理，仅 user 问句与 assistant 结论压缩';
