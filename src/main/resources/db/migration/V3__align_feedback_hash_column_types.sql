-- V2 曾将哈希列建为 CHAR(64)，而 JPA 使用 VARCHAR(64)；统一为 VARCHAR 避免 Hibernate 校验失败。
-- 哈希值仍由 SHA-256 生成并固定为 64 个十六进制字符，类型调整不改变去重语义或既有数据。
ALTER TABLE feedback_event
    ALTER COLUMN external_ref_hash TYPE VARCHAR(64),
    ALTER COLUMN content_hash TYPE VARCHAR(64);
