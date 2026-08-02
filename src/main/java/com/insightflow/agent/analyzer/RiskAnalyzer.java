package com.insightflow.agent.analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.agent.InsightAgent;
import com.insightflow.agent.LlmMetrics;
import com.insightflow.agent.dto.RiskResult;
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
 * 游戏运营风险分析 Agent。
 *
 * <p>它仅提供人工研判的风险输入，不会自动修改告警或外部策略；正式调用的审计与分类、情感 Agent 使用同一服务。</p>
 */
@Component
@Conditional(AgentApiKeyPresentCondition.class)
public class RiskAnalyzer implements InsightAgent<RiskResult> {

    /** 模型调用边界由 Spring 配置层装配。 */
    private final LiteralChatModelCaller literalChatModelCaller;
    /** 自由文本必须转换为风险 DTO 才能进入领域投影。 */
    private final ObjectMapper objectMapper;
    /** 集中 Prompt 目录负责正文与版本。 */
    private final OperationalPromptCatalog promptCatalog;
    /** AgentRun 服务维护工作区隔离与 Trace 生命周期。 */
    private final AgentRunService agentRunService;
    /** 实际模型名进入审计，方便比较供应商或模型变更。 */
    private final String modelName;

    /** 显式注入依赖，支持真实执行和无外部调用的单元测试。 */
    public RiskAnalyzer(
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

    @Override public String systemPrompt() { return promptCatalog.risk().systemPrompt(); }
    @Override public String promptVersion() { return promptCatalog.risk().version(); }
    @Override public Class<RiskResult> outputSchema() { return RiskResult.class; }

    /** 纯探针不具备工作区归属时不写审计表。 */
    @Override
    public RiskResult execute(String userInput) {
        return execute(null, userInput);
    }

    /** 每次正式风险调用先创建 Trace，解析失败也会更新为受控失败状态。 */
    public RiskResult execute(UUID workspacePublicId, String userInput) {
        long start = System.currentTimeMillis();
        AgentRun run = workspacePublicId == null ? null : agentRunService.start(workspacePublicId,
                new AgentRunService.StartRequest("risk", promptVersion(), modelName, "none", userInput));
        LlmMetrics.logStarted("Risk", promptVersion(), userInput);
        ChatResponse response;
        try {
            response = literalChatModelCaller.call(systemPrompt(), userInput);
        } catch (RuntimeException exception) {
            LlmMetrics.logFailure("Risk", promptVersion(), start, "model_call");
            fail(workspacePublicId, run, start);
            return null;
        }
        LlmMetrics.log("Risk", promptVersion(), start, response);
        try {
            String content = response.getResult().getOutput().getContent();
            RiskResult result = objectMapper.readValue(LlmMetrics.extractJson(content), outputSchema());
            succeed(workspacePublicId, run, content, response, start);
            return result;
        } catch (Exception exception) {
            LlmMetrics.logFailure("Risk", promptVersion(), start, "response_parse");
            fail(workspacePublicId, run, start);
            return null;
        }
    }

    /** 成功记录最终输出和供应商真实 Usage，不保存系统提示词与推理文本。 */
    private void succeed(UUID workspacePublicId, AgentRun run, String output, ChatResponse response, long start) {
        if (run == null) return;
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        agentRunService.succeed(workspacePublicId, run.getPublicId(), new AgentRunService.Completion(
                output, null, usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getGenerationTokens(), usage == null ? null : usage.getTotalTokens(),
                System.currentTimeMillis() - start));
    }

    /** 失败只记录统一码，避免上游异常内容泄露到审计数据。 */
    private void fail(UUID workspacePublicId, AgentRun run, long start) {
        if (run != null) agentRunService.fail(workspacePublicId, run.getPublicId(), System.currentTimeMillis() - start);
    }
}
