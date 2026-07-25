-- P4 在既有分析报告上补充运营报告范围与证据快照：报告不重新推导告警，而是引用已人工确认调查的冻结依据。
-- 旧报告统一视为 WEEKLY，保证现有历史记录在非空约束后仍可读；report_evidence_json 是生成时的只读快照，不随调查后续变化回写。
ALTER TABLE analysis_report ADD COLUMN operational_scope VARCHAR(30) NOT NULL DEFAULT 'WEEKLY';
ALTER TABLE analysis_report ADD COLUMN report_evidence_json JSONB;

-- 只允许当前明确支持的三种运营范围，避免客户端传入未定义语义并污染报告历史。
ALTER TABLE analysis_report ADD CONSTRAINT ck_analysis_report_operational_scope
    CHECK (operational_scope IN ('DAILY', 'WEEKLY', 'VERSION_REVIEW'));
