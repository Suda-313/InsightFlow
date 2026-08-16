package com.insightflow.investigation.window;

import com.insightflow.entity.Alert;

/**
 * 在调查首次入队时为默认窗口提供受限建议。
 *
 * <p>规划器只能返回枚举文本，绝不能传递起止日期、SQL 或 Tool 列表。冻结服务仍负责
 * 校验结果并通过 {@link InvestigationWindowResolver} 生成真实边界。</p>
 */
public interface InvestigationWindowPlanner {

    /** 使用不可变 Alert 快照提出建议；失败以 failureReason 表达，不能抛出阻断调查创建。 */
    Proposal propose(Alert alert, InvestigationWindowSelection defaultSelection);

    /** 原始枚举文本保留给服务端校验，以便审计非法模型返回并安全回退。 */
    record Proposal(String windowType, String reason, String failureReason) {
        /** Planner 不可用时的稳定回退结果。 */
        public static Proposal unavailable(String failureReason) {
            return new Proposal(null, null, failureReason);
        }
    }

    /** 供纯单元测试与不启用 Agent 的构造器使用的零副作用 Planner。 */
    static InvestigationWindowPlanner disabled() {
        return (alert, defaultSelection) -> Proposal.unavailable("planner_disabled");
    }
}
