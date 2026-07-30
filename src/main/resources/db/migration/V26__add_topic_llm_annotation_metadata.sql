-- Phase C：Pack 级 LLM Topic Skill 在 L2 标注行上冻结 Prompt 版本并记录 LLM 置信度，
-- 便于追溯「规则零命中 → LLM 补标 → 仍落 topic_general」的统计口径；L1 事实仍在 feedback_issue_link。
ALTER TABLE feedback_projection_annotation
    ADD COLUMN topic_llm_prompt_version VARCHAR(80) NULL,
    ADD COLUMN topic_llm_confidence DOUBLE PRECISION NULL;
