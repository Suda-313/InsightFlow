-- 金标 evidence 可选 requirement_key：同 key 内 OR、跨 key AND。
ALTER TABLE rag_gold_case_evidence
    ADD COLUMN requirement_key VARCHAR(120);

COMMENT ON COLUMN rag_gold_case_evidence.requirement_key IS
    '可选证据需求组键；同组内任一 chunk 命中即满足该组，不同组之间 AND。';
