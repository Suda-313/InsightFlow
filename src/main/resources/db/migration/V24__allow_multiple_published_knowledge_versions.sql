-- 同一知识文档允许多个已发布版本并存；旧版仅在被用户显式失效或发布时勾选「下线旧版」后才退出 RAG。
-- 去掉 V13 的部分唯一索引，保留 (document_id, version_no) 与状态 CHECK，审计链与版本号单调性不变。

DROP INDEX IF EXISTS uk_knowledge_document_published_version;
