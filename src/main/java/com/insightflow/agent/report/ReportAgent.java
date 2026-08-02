package com.insightflow.agent.report;

import com.insightflow.agent.LlmMetrics;
import com.insightflow.config.AgentApiKeyPresentCondition;
import com.insightflow.entity.AgentRun;
import com.insightflow.prompt.LiteralChatModelCaller;
import com.insightflow.prompt.OperationalPromptCatalog;
import com.insightflow.service.AgentRunService;
import java.util.UUID;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * 报告生成 Agent：根据已聚合的脱敏指标生成运营周报叙述。
 *
 * <p>它不读取原始 CSV 或完整用户反馈；工作区调用通过 AgentRun 保存最终报告、模型版本、耗时和 Usage，
 * 不保存系统提示词正文或任何模型推理内容。</p>
 */
@Component
@Conditional(AgentApiKeyPresentCondition.class)
public class ReportAgent {

    /** 模型调用边界由 Spring 配置层装配，报告 Agent 不处理供应商密钥。 */
    private final LiteralChatModelCaller literalChatModelCaller;
    /** 统一的 Prompt 正文与版本来源，禁止在报告类内新增静态提示词。 */
    private final OperationalPromptCatalog promptCatalog;
    /** 统一 AgentRun 生命周期，确保报告与聊天、分析调用使用相同审计语义。 */
    private final AgentRunService agentRunService;
    /** 实际模型名进入运行审计，供评测和成本基线比较。 */
    private final String modelName;

    /**
     * 保留协调与工具依赖的装配边界；当前报告只消费已完成的聚合数据，
     * 不在 Agent 内直接触发对账或外部工具调用。
     */
    public ReportAgent(
            LiteralChatModelCaller literalChatModelCaller,
            ReconciliationEngine reconciliationEngine,
            ReportTools reportTools,
            OperationalPromptCatalog promptCatalog,
            AgentRunService agentRunService,
            @Value("${spring.ai.openai.chat.options.model:unknown}") String modelName) {
        this.literalChatModelCaller = literalChatModelCaller;
        this.promptCatalog = promptCatalog;
        this.agentRunService = agentRunService;
        this.modelName = modelName;
    }

    /** 返回当前报告 Prompt 版本，供日志和审计结果关联。 */
    public String promptVersion() {
        return promptCatalog.report().version();
    }

    /** 无工作区探针或纯单测不写入无法隔离的 AgentRun。 */
    public String generate(MergedData mergedData) {
        return generate(null, mergedData);
    }

    /**
     * 基于聚合数据生成报告并记录 Trace；上游已负责聚合和脱敏，模型异常将更新为受控失败记录后继续上抛。
     */
    public String generate(UUID workspacePublicId, MergedData mergedData) {
        String userPrompt = promptCatalog.renderReportUserPrompt(
                mergedData.actualTicketCount(),
                mergedData.issueMentions(),
                mergedData.expressionMentions());
        long start = System.currentTimeMillis();
        AgentRun run = workspacePublicId == null ? null : agentRunService.start(workspacePublicId,
                new AgentRunService.StartRequest("report", promptVersion(), modelName, "none", userPrompt));
        LlmMetrics.logStarted("Report", promptVersion(), userPrompt);
        try {
            ChatResponse response = literalChatModelCaller.call(
                    promptCatalog.report().systemPrompt(), userPrompt);
            LlmMetrics.log("Report", promptVersion(), start, response);
            String output = response.getResult().getOutput().getContent();
            succeed(workspacePublicId, run, output, response, start);
            return output;
        } catch (RuntimeException exception) {
            LlmMetrics.logFailure("Report", promptVersion(), start, "model_call");
            fail(workspacePublicId, run, start);
            throw exception;
        }
    }

    /** 成功时仅写入最终报告文本与服务商真实 Usage。 */
    private void succeed(UUID workspacePublicId, AgentRun run, String output, ChatResponse response, long start) {
        if (run == null) return;
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        agentRunService.succeed(workspacePublicId, run.getPublicId(), new AgentRunService.Completion(
                output, null, usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getGenerationTokens(), usage == null ? null : usage.getTotalTokens(),
                System.currentTimeMillis() - start));
    }

    /** 失败记录固定错误码，异常正文只留在服务端受控日志。 */
    private void fail(UUID workspacePublicId, AgentRun run, long start) {
        if (run != null) agentRunService.fail(workspacePublicId, run.getPublicId(), System.currentTimeMillis() - start);
    }
}
