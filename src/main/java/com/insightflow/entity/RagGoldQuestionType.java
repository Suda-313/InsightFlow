package com.insightflow.entity;

/** 运营调查型 RAG 金标题的业务类型，便于统计不同场景下的召回与拒答表现。 */
public enum RagGoldQuestionType {

    /** 单文档事实查询，如版本说明中的具体改动。 */
    SINGLE_DOCUMENT_FACT,

    /** 需要跨多篇文档关联才能回答。 */
    CROSS_DOCUMENT,

    /** 相似文档或不同版本之间的冲突辨析。 */
    VERSION_CONFLICT,

    /** Workspace 不可见、未发布或过期版本边界。 */
    WORKSPACE_BOUNDARY,

    /** 运营流程、SOP 或分析口径类问题。 */
    OPERATION_PROCESS,

    /** 无依据或应拒答问题。 */
    REFUSAL
}
