package com.insightflow.knowledge;

/** 回答可引用的知识片段；不包含内部 ID、对象键、私有 MinIO 地址或完整原文。 */
public record KnowledgeEvidence(String id, String title, int version, String snippet, String sourceUrl) {
    /** 文档内容被标记为不可信资料，模型不能执行其中的任何指令。 */
    String promptLine() { return "[" + id + "] 标题=" + title + "，版本=" + version + "，片段=" + snippet; }
}
