-- 删除 val-80 金标快照以便同版本标签重导入（仅 VALIDATION split）。
DELETE FROM rag_gold_case
WHERE dataset_id IN (
    SELECT id FROM rag_gold_dataset
    WHERE dataset_key = 'ops-rag-v1' AND dataset_version = 'val-80'
);
DELETE FROM rag_gold_dataset
WHERE dataset_key = 'ops-rag-v1' AND dataset_version = 'val-80';
