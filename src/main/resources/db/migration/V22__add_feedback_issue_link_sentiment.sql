-- 主题级情绪属于“反馈—主题”关联，而不是整条评论：同一条长评可对不同主题呈现相反情绪。
-- 允许为空以兼容历史投影；新投影必须写入受控枚举 positive/negative/neutral/mixed。
ALTER TABLE feedback_issue_link
    ADD COLUMN sentiment VARCHAR(20);

ALTER TABLE feedback_issue_link
    ADD CONSTRAINT ck_feedback_issue_link_sentiment
    CHECK (sentiment IS NULL OR sentiment IN ('positive', 'negative', 'neutral', 'mixed'));
