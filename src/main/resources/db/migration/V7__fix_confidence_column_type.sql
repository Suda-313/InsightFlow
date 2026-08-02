-- 修复 feedback_issue_link.confidence 列类型：NUMERIC(5,4) 与 JPA double 映射不兼容，
-- Hibernate 期望 float(53) / DOUBLE PRECISION。
ALTER TABLE feedback_issue_link
    ALTER COLUMN confidence TYPE DOUBLE PRECISION;