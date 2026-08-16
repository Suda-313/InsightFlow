package com.insightflow.investigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.Alert;
import com.insightflow.entity.InvestigationCase;
import com.insightflow.investigation.window.InvestigationWindow;
import com.insightflow.investigation.window.InvestigationWindowPolicy;
import com.insightflow.investigation.window.InvestigationWindowPlanner;
import com.insightflow.investigation.window.InvestigationWindowResolver;
import com.insightflow.investigation.window.InvestigationWindowSelection;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 在异步证据执行前将调查窗口冻结到 InvestigationCase。
 *
 * <p>本服务只处理确定性默认策略；后续可选 Planner 只能在调用此服务前给出经白名单校验的选择，
 * 不能提交自由时间范围。序列化失败会阻断任务创建，避免 Worker 面对半完成且不可复现的计划。</p>
 */
@Service
public class InvestigationPlanFreezer {

    private final InvestigationWindowPolicy windowPolicy;
    private final InvestigationWindowResolver windowResolver;
    private final InvestigationWindowPlanner windowPlanner;
    private final ObjectMapper objectMapper;

    /** 组合策略、边界解析器与项目统一 JSON 序列化器。 */
    public InvestigationPlanFreezer(
            InvestigationWindowPolicy windowPolicy,
            InvestigationWindowResolver windowResolver,
            InvestigationWindowPlanner windowPlanner,
            ObjectMapper objectMapper) {
        this.windowPolicy = windowPolicy;
        this.windowResolver = windowResolver;
        this.windowPlanner = windowPlanner;
        this.objectMapper = objectMapper;
    }

    /** 兼容无 Agent 的纯规则构造；生产装配会注入真实但可回退的 Planner。 */
    public InvestigationPlanFreezer(
            InvestigationWindowPolicy windowPolicy,
            InvestigationWindowResolver windowResolver,
            ObjectMapper objectMapper) {
        this(windowPolicy, windowResolver, InvestigationWindowPlanner.disabled(), objectMapper);
    }

    /** 首次创建时一次性选择并冻结计划；已冻结的 Case 绝不重新调用 Planner。 */
    public void freezePlan(InvestigationCase investigation, Alert alert) {
        if (investigation.getPlanJson() != null && !investigation.getPlanJson().isBlank()) {
            return;
        }
        InvestigationWindowSelection selection = windowPolicy.defaultFor(alert);
        InvestigationWindowPlanner.Proposal proposal = windowPlanner.propose(alert, selection);
        InvestigationWindowSelection finalSelection = selection;
        boolean plannerUsed = false;
        String fallbackReason = proposal.failureReason();
        if (fallbackReason == null) {
            try {
                finalSelection = InvestigationWindowSelection.valueOf(proposal.windowType());
                plannerUsed = true;
            } catch (Exception invalid) {
                fallbackReason = "planner_invalid_window_type";
            }
        }
        List<InvestigationWindow> windows = windowResolver.resolve(alert.getBucketStart(), finalSelection);
        try {
            investigation.freezePlan(objectMapper.writeValueAsString(new FrozenInvestigationPlan(
                    2,
                    selection.name(),
                    proposal.windowType(),
                    finalSelection.name(),
                    plannerUsed,
                    plannerUsed ? cap(proposal.reason()) : null,
                    plannerUsed ? null : fallbackReason,
                    windows.stream().map(FrozenWindow::from).toList())));
        } catch (Exception exception) {
            throw new IllegalStateException("无法冻结调查时间窗口", exception);
        }
    }

    /** 保留旧入口供既有调用方过渡，语义仍为完整的一次性冻结而非仅默认窗口。 */
    public void freezeDefaultPlan(InvestigationCase investigation, Alert alert) {
        freezePlan(investigation, alert);
    }

    /** 仅保存可复核的枚举、服务端边界和受控回退原因，不保存模型 Prompt 或原始反馈。 */
    private record FrozenInvestigationPlan(
            int schemaVersion,
            String defaultWindowType,
            String plannerWindowType,
            String finalWindowType,
            boolean plannerUsed,
            String plannerReason,
            String fallbackReason,
            List<FrozenWindow> windows) {
    }

    /** 计划只保存可审计的简短理由，避免意外把长文本或敏感信息写入调查表。 */
    private String cap(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= 300 ? value : value.substring(0, 300);
    }

    /** 显式 ISO 字符串避免全局 ObjectMapper 是否写 epoch 时间戳影响冻结计划的可读性和稳定性。 */
    private record FrozenWindow(
            String type,
            String anchorTime,
            String currentStart,
            String currentEnd,
            String previousStart,
            String previousEnd) {
        private static FrozenWindow from(InvestigationWindow window) {
            return new FrozenWindow(
                    window.type().name(),
                    window.anchorTime().toString(),
                    window.currentStart().toString(),
                    window.currentEnd().toString(),
                    window.previousStart().toString(),
                    window.previousEnd().toString());
        }
    }
}
