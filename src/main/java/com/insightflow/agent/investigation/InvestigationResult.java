package com.insightflow.agent.investigation;

import java.util.List;
import java.util.Objects;

/**
 * 一次单 Agent 调查的计划与证据快照。
 *
 * <p>该对象是 Tool 层和 Prompt 层的边界：前者只产生受控事实，后者只消费本对象渲染的索引文本。
 * 它不包含模型回答、思维链或数据库内部键，可安全序列化到 AgentRun 的 evidence_json。</p>
 */
public record InvestigationResult(InvestigationPlan plan, List<InvestigationEvidence> evidence) {

    /** 冻结证据顺序，使 Prompt、审计记录与前端复核看到同一份证据索引。 */
    public InvestigationResult {
        plan = Objects.requireNonNull(plan, "调查计划不能为空");
        evidence = List.copyOf(evidence);
    }

    /**
     * 将计划和证据渲染为模型只能引用的事实区；证据不足项保留原文，禁止模型把它改写为确定性结论。
     */
    public String renderForPrompt() {
        StringBuilder context = new StringBuilder("\n## 调查计划\n");
        context.append("意图：").append(plan.intent()).append("\n");
        context.append("已调用 Tool：").append(plan.tools()).append("\n\n## 证据索引\n");
        for (InvestigationEvidence item : evidence) {
            context.append("- [").append(item.id()).append("] ")
                    .append(item.title()).append("：").append(item.content());
            if (!item.sufficient()) {
                context.append("（数据不足）");
            }
            context.append("\n");
        }
        return context.toString();
    }
}
