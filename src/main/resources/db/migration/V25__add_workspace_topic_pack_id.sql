-- Workspace 级 Topic Pack 绑定：nullable 表示沿用全局默认 Pack（insightflow.analysis.topic-pack-directory）。
-- 换 Pack 只影响后续新投影的 L1 规则来源；历史 feedback_issue_link 保留既有 canonical_key，不做自动迁移。
ALTER TABLE workspace ADD COLUMN topic_pack_id VARCHAR(80) NULL;

COMMENT ON COLUMN workspace.topic_pack_id IS 'Workspace 绑定的 L1 Topic Pack 标识（pack.toml pack_id）；NULL 时回退全局默认 Pack';
