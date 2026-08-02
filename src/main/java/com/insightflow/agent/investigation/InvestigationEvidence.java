package com.insightflow.agent.investigation;

import java.util.Objects;

/**
 * 一项可展示、可写入审计快照的受控调查证据。
 *
 * <p>证据 ID 只由服务端的 Tool、主题 key 和固定窗口组成，不含内部主键、原始反馈 ID 或用户输入。
 * {@code sufficient=false} 表示数据不足而不是空结果，模型必须在“未知项”中保留该限制。</p>
 */
public record InvestigationEvidence(
        /** 供模型回答和用户复核引用的稳定证据标识。 */
        String id,
        /** 产生此事实的只读 Tool 白名单类型。 */
        InvestigationToolType tool,
        /** 面向用户的简短来源标题。 */
        String title,
        /** 已聚合或脱敏、截断后的证据正文。 */
        String content,
        /** 当前数据是否足以回答对应问题，而非是否查询成功。 */
        boolean sufficient,
        String sourceUrl) {

    /** P2 证据没有来源链接；P3 知识证据才会填充应用内 sourceUrl。 */
    public InvestigationEvidence(String id, InvestigationToolType tool, String title, String content, boolean sufficient) {
        this(id, tool, title, content, sufficient, null);
    }

    /** 证据契约拒绝空 ID 或正文，避免模型引用一个无法复核的占位来源。 */
    public InvestigationEvidence {
        id = Objects.requireNonNull(id, "证据 ID 不能为空");
        tool = Objects.requireNonNull(tool, "Tool 类型不能为空");
        title = Objects.requireNonNull(title, "证据标题不能为空");
        content = Objects.requireNonNull(content, "证据内容不能为空");
    }
}
