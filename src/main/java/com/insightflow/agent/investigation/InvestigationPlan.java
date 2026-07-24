package com.insightflow.agent.investigation;

import java.util.List;
import java.util.Objects;

/**
 * 单次聊天在模型调用前确定的只读调查计划。
 *
 * <p>计划不保存用户问题正文、内部键或模型推理；它只保存可审计的意图和 Tool 白名单，
 * 后续会作为 AgentRun 证据快照的一部分，帮助定位模型实际基于哪些数据回答。</p>
 */
public record InvestigationPlan(InvestigationIntent intent, List<InvestigationToolType> tools) {

    /**
     * 计划创建时冻结 Tool 顺序；顺序代表先后读取语义，禁止调用方在执行中追加未规划 Tool。
     */
    public InvestigationPlan {
        intent = Objects.requireNonNull(intent, "intent 不能为空");
        tools = List.copyOf(tools);
        if (tools.isEmpty()) {
            throw new IllegalArgumentException("调查计划至少需要一个只读 Tool");
        }
    }
}
