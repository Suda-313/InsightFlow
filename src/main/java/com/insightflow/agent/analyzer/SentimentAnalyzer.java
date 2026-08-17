package com.insightflow.agent.analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.agent.InsightAgent;
import com.insightflow.agent.LlmMetrics;
import com.insightflow.agent.dto.SentimentResult;
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
 * 游戏客服情感分析 Agent。
 *
 * <p>只输出下游可聚合的情感和紧急程度枚举；正式工作区调用会记录版本、模型、脱敏输入摘要和 Usage，
 * 不保存原始反馈或模型内部推理。</p>
 */
@Component
@Conditional(AgentApiKeyPresentCondition.class)
public class SentimentAnalyzer implements InsightAgent<SentimentResult> {

    /** 统一模型调用边界，Agent 不创建客户端或读取密钥。 */
    private final LiteralChatModelCaller literalChatModelCaller;
    /** 结构化结果按 DTO 契约解析，解析失败不向日志泄露模型原文。 */
    private final ObjectMapper objectMapper;
    /** 集中提示词目录是唯一的正文与版本来源。 */
    private final OperationalPromptCatalog promptCatalog;
    /** 运行审计服务负责工作区隔离与输入脱敏。 */
    private final AgentRunService agentRunService;
    /** 模型名称作为成本、延迟和效果比较的必要维度。 */
    private final String modelName;

    /** 通过构造器显式注入模型、Prompt 与审计依赖，保证可替换性。 */
    public SentimentAnalyzer(
            LiteralChatModelCaller literalChatModelCaller,
            ObjectMapper objectMapper,
            OperationalPromptCatalog promptCatalog,
            AgentRunService agentRunService,
            @Value("${spring.ai.openai.chat.options.model:unknown}") String modelName) {
        this.literalChatModelCaller = literalChatModelCaller;
        this.objectMapper = objectMapper;
        this.promptCatalog = promptCatalog;
        this.agentRunService = agentRunService;
        this.modelName = modelName;
    }

    @Override public String systemPrompt() { return promptCatalog.sentiment().systemPrompt(); }
    @Override public String promptVersion() { return promptCatalog.sentiment().version(); }
    @Override public Class<SentimentResult> outputSchema() { return SentimentResult.class; }

    /** 无工作区探针调用不生成无归属审计记录。 */
    @Override
    public SentimentResult execute(String userInput) {
        return execute(null, userInput);
    }

    /** 工作区调用在模型请求前创建 Trace，成功或失败均收敛为最终状态。 */
    public SentimentResult execute(UUID workspacePublicId, String userInput) {
        long start = System.currentTimeMillis();
        AgentRun run = workspacePublicId == null ? null : agentRunService.start(workspacePublicId,
                new AgentRunService.StartRequest("sentiment", promptVersion(), modelName, "none", userInput));
        LlmMetrics.logStarted("Sentiment", promptVersion(), userInput);
        ChatResponse response;
        try {
            response = literalChatModelCaller.call(systemPrompt(), userInput);
        } catch (RuntimeException exception) {
            LlmMetrics.logFailure("Sentiment", promptVersion(), start, "model_call");
            fail(workspacePublicId, run, start);
            return null;
        }
        LlmMetrics.log("Sentiment", promptVersion(), start, response);
        try {
            String content = response.getResult().getOutput().getText();
            SentimentResult result = objectMapper.readValue(LlmMetrics.extractJson(content), outputSchema());
            succeed(workspacePublicId, run, content, response, start);
            return result;
        } catch (Exception exception) {
            LlmMetrics.logFailure("Sentiment", promptVersion(), start, "response_parse");
            fail(workspacePublicId, run, start);
            return null;
        }
    }

    /** 最终输出与真实 Usage 写入审计，Usage 缺失时保持 null 而不估算。 */
    private void succeed(UUID workspacePublicId, AgentRun run, String output, ChatResponse response, long start) {
        if (run == null) return;
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        agentRunService.succeed(workspacePublicId, run.getPublicId(), new AgentRunService.Completion(
                output, null, toLong(usage == null ? null : usage.getPromptTokens()),
                toLong(usage == null ? null : usage.getCompletionTokens()), toLong(usage == null ? null : usage.getTotalTokens()),
                System.currentTimeMillis() - start));
    }

    /** Spring AI 1.1 返回 Integer token，审计模型使用 Long 以兼容数据库聚合。 */
    private Long toLong(Integer value) {
        return value == null ? null : value.longValue();
    }

    /** 异常详情只写受控日志，审计表仅保存固定失败码。 */
    private void fail(UUID workspacePublicId, AgentRun run, long start) {
        if (run != null) agentRunService.fail(workspacePublicId, run.getPublicId(), System.currentTimeMillis() - start);
    }
}
