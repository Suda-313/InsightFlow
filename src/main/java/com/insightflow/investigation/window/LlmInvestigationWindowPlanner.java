package com.insightflow.investigation.window;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.Alert;
import com.insightflow.prompt.LiteralChatModelCaller;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 使用既有 ChatModel 的受限窗口选择器。
 *
 * <p>没有单独创建模型或保存 Prompt/原始响应；模型仅看到 Alert 的聚合事实，输出也只会被
 * 解析成 windowType 与简短原因。所有异常都转换为回退原因，由冻结服务继续创建调查。</p>
 */
@Component
public class LlmInvestigationWindowPlanner implements InvestigationWindowPlanner {

    /** 复用已有 Agent ChatModel；Agent 未配置时 Provider 返回空而不是阻止应用启动。 */
    private final ObjectProvider<LiteralChatModelCaller> chatModelCallerProvider;
    /** 项目统一 JSON 解析器只读取两个白名单字段。 */
    private final ObjectMapper objectMapper;
    /** 默认关闭，避免未明确启用时每条告警触发额外模型调用。 */
    private final boolean enabled;

    /** 生产构造器保持 Agent 可选，调查基础流程不依赖模型服务可用。 */
    public LlmInvestigationWindowPlanner(
            ObjectProvider<LiteralChatModelCaller> chatModelCallerProvider,
            ObjectMapper objectMapper,
            @Value("${insightflow.investigation.window-planner.enabled:false}") boolean enabled) {
        this.chatModelCallerProvider = chatModelCallerProvider;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    /** 仅返回白名单候选的原始文本；合法性判断留在冻结层，避免模型拥有业务控制权。 */
    @Override
    public Proposal propose(Alert alert, InvestigationWindowSelection defaultSelection) {
        if (!enabled) return Proposal.unavailable("planner_disabled");
        LiteralChatModelCaller caller = chatModelCallerProvider.getIfAvailable();
        if (caller == null) return Proposal.unavailable("agent_disabled");
        try {
            String raw = caller.callContent(systemPrompt(), userContext(alert, defaultSelection));
            if (raw == null || raw.isBlank()) return Proposal.unavailable("planner_empty_response");
            JsonNode response = objectMapper.readTree(raw);
            String type = text(response, "windowType");
            if (type == null) return Proposal.unavailable("planner_missing_window_type");
            return new Proposal(type, text(response, "reason"), null);
        } catch (Exception ignored) {
            // 网络、超时、模型或 JSON 错误都不应阻断调查；细节不进入 plan_json 或日志。
            return Proposal.unavailable("planner_error");
        }
    }

    /** 固定系统指令限定模型只能给出枚举，不允许其描述或决定实际时间边界。 */
    private String systemPrompt() {
        return "你是调查窗口选择器。仅返回 JSON：{\"windowType\":\"SHORT_TERM|WEEKLY|BOTH\",\"reason\":\"简短依据\"}。"
                + "不得返回日期、天数、SQL、Tool 或任何其他字段。";
    }

    /** AlertContext 只包含持久化的聚合异常事实，不含原始反馈、完整 Prompt 或敏感标识。 */
    private String userContext(Alert alert, InvestigationWindowSelection defaultSelection) {
        return String.format("issueId=%d; bucketStart=%s; currentCount=%d; baselineEwma=%.2f; baselineStddev=%.2f; zScore=%.2f; effectiveThreshold=%d; status=%s; defaultWindowType=%s",
                alert.getIssueId(), alert.getBucketStart(), alert.getCurrentCount(), alert.getBaselineEwma(),
                alert.getBaselineStddev(), alert.getZScore(), alert.getEffectiveThreshold(), alert.getStatus(), defaultSelection);
    }

    /** JSON 空值与空白都按缺失处理，避免把无意义值冻结为模型选择。 */
    private String text(JsonNode response, String field) {
        String value = response.path(field).asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
