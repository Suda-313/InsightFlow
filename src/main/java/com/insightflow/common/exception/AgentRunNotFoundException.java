package com.insightflow.common.exception;

import java.util.UUID;

/**
 * 表示 AgentRun Trace 不存在或不属于当前工作区。
 *
 * <p>统一使用 404，避免调用者根据不同响应探测其他工作区的模型调用元数据。</p>
 */
public class AgentRunNotFoundException extends RuntimeException {

    /** 仅携带外部 Trace，不泄露内部主键和工作区信息。 */
    public AgentRunNotFoundException(UUID traceId) {
        super("Agent run not found: " + traceId);
    }
}
