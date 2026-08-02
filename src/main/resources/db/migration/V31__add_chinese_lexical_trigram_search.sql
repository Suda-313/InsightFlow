-- 中文词法检索：启用 pg_trgm 字符级 trigram，替代 simple FTS 对中文几乎零命中的问题。
-- 索引按 knowledge_chunk / knowledge_document 行存储，Workspace 隔离仍由查询 visible CTE 保证。

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_lexical_text_trgm
    ON knowledge_chunk USING GIN (lexical_text gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_section_heading_trgm
    ON knowledge_chunk USING GIN (section_heading gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_content_trgm
    ON knowledge_chunk USING GIN (content gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_knowledge_document_title_trgm
    ON knowledge_document USING GIN (title gin_trgm_ops);
