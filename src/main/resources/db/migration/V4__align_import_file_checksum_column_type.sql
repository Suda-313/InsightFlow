-- V2 的文件 SHA-256 校验列为 CHAR(64)，而实体声明 VARCHAR(64)；新增迁移保持历史脚本不可变。
-- 该调整不改变已有摘要值，仅使数据库类型和 Hibernate 的严格 schema validation 一致。
ALTER TABLE import_file
    ALTER COLUMN checksum_sha256 TYPE VARCHAR(64);
