-- 扩展运营调查型 RAG 的文档类型与版本级语料元数据。
-- 原有四类 RELEASE_NOTE / KNOWN_ISSUE / SUPPORT_SOP / SENTIMENT_PLAYBOOK 保持兼容；
-- 新增 OPERATION_EVENT（时效性运营事实）与 POSTMORTEM（已完成事件复盘）。

ALTER TABLE knowledge_document DROP CONSTRAINT ck_knowledge_document_type;

ALTER TABLE knowledge_document ADD CONSTRAINT ck_knowledge_document_type CHECK (
    document_type IN (
        'RELEASE_NOTE', 'KNOWN_ISSUE', 'SUPPORT_SOP', 'SENTIMENT_PLAYBOOK',
        'OPERATION_EVENT', 'POSTMORTEM'
    )
);

-- 元数据挂在版本级：同一逻辑文档的不同上传可能对应不同来源、适用窗口与事实边界。
ALTER TABLE knowledge_document_version
    ADD COLUMN source_url VARCHAR(2000),
    ADD COLUMN source_collected_at TIMESTAMPTZ,
    ADD COLUMN effective_from TIMESTAMPTZ,
    ADD COLUMN effective_to TIMESTAMPTZ,
    ADD COLUMN owner VARCHAR(100),
    ADD COLUMN fact_boundary VARCHAR(2000);

-- 检索按适用窗口过滤时，需要快速定位仍在有效期内的已发布版本。
CREATE INDEX idx_knowledge_document_version_effective_window
    ON knowledge_document_version (effective_from, effective_to)
    WHERE status = 'PUBLISHED';
