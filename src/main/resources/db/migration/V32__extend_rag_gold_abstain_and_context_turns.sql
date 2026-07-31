-- 扩展金标题型以支持弃权负样本（CHITCHAT / NO_ANSWER），并为多轮评测保存前序对话。
-- context_turns 仅存评测用前序轮次，不保存模型思维链；null 表示单轮自足题。

ALTER TABLE rag_gold_case DROP CONSTRAINT ck_rag_gold_case_question_type;

ALTER TABLE rag_gold_case ADD CONSTRAINT ck_rag_gold_case_question_type CHECK (
    question_type IN (
        'SINGLE_DOCUMENT_FACT', 'CROSS_DOCUMENT', 'VERSION_CONFLICT',
        'WORKSPACE_BOUNDARY', 'OPERATION_PROCESS', 'REFUSAL',
        'CHITCHAT', 'NO_ANSWER'
    )
);

ALTER TABLE rag_gold_case ADD COLUMN context_turns JSONB;
