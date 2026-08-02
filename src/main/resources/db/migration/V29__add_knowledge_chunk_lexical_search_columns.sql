-- 为词法检索补齐标题/章节/版本等可见字段；lexical_text 在发布时写入，旧切片在 SQL 中回退拼接。
-- section_heading 与 lexical_text 均按 Workspace 隔离的 knowledge_chunk 行存储，不暴露跨组织数据。

ALTER TABLE knowledge_chunk
    ADD COLUMN IF NOT EXISTS section_heading TEXT;

ALTER TABLE knowledge_chunk
    ADD COLUMN IF NOT EXISTS lexical_text TEXT;

-- 词法索引：优先已物化的 lexical_text，否则查询层仍会用 title/type/version/content 回退。
CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_lexical_tsv
    ON knowledge_chunk USING GIN (
        to_tsvector('simple', coalesce(lexical_text, content))
    );
