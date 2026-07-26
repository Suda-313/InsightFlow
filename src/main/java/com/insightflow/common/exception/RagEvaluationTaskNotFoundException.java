package com.insightflow.common.exception;

import java.util.UUID;

/**
 * 表示 RAG 评测任务不存在，或不属于当前 Workspace。
 *
 * <p>两种情况统一返回 404，避免任务公开 UUID 成为跨工作区资源探测入口。</p>
 */
public class RagEvaluationTaskNotFoundException extends RuntimeException {

    /** 仅保留客户端传入的公开标识，异常中不携带内部主键或实际归属。 */
    public RagEvaluationTaskNotFoundException(UUID publicId) {
        super("RAG evaluation task not found: " + publicId);
    }
}
