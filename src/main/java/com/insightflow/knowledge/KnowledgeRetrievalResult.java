package com.insightflow.knowledge;

import java.util.List;

/** 受控 RAG 调用的可审计结果，只保存计划轮次和可展示证据，不保存模型思维链。 */
public record KnowledgeRetrievalResult(int rounds, List<KnowledgeEvidence> evidence) {
    /** 提示词只使用截断片段，防止单个原文占满上下文或把文档指令当作系统规则。 */
    public String renderForPrompt() {
        if (evidence.isEmpty()) return "\n## 企业知识证据\n未检索到已发布企业知识。\n";
        return "\n## 企业知识证据\n" + evidence.stream().map(KnowledgeEvidence::promptLine).collect(java.util.stream.Collectors.joining("\n"));
    }
}
