package com.insightflow.entity;

/**
 * 知识文档版本的可检索生命周期。
 *
 * <p>状态不是 UI 文案：只有 {@link #PUBLISHED} 可以参与 RAG；其余状态必须在仓储 SQL 和服务层
 * 同时被排除，防止未审核或历史版本影响模型回答。</p>
 */
public enum KnowledgeVersionStatus {

    /** 原文件已经保存但尚未完成发布物构建，不能被检索或引用。 */
    PENDING_REVIEW,

    /** 切片与嵌入均成功后的唯一当前版本，可供当前可见范围内的 RAG 使用。 */
    PUBLISHED,

    /** 已被新版本替代或人工下线；保留元数据和来源供历史回答审计。 */
    EXPIRED,

    /** 逻辑删除状态；不物理移除对象索引，以免破坏既有 Trace 的可复核性。 */
    DELETED
}
