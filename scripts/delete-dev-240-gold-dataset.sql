-- 删除 dev-240 金标快照以便同版本标签重导入（仅 DEVELOPMENT split）。
DELETE FROM rag_gold_case
WHERE dataset_id IN (
    SELECT id FROM rag_gold_dataset
    WHERE dataset_key = 'ops-rag-v1' AND dataset_version = 'dev-240'
);
DELETE FROM rag_gold_dataset
WHERE dataset_key = 'ops-rag-v1' AND dataset_version = 'dev-240';
