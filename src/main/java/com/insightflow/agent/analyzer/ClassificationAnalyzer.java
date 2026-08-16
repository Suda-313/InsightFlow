package com.insightflow.agent.analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.agent.InsightAgent;
import com.insightflow.agent.LlmMetrics;
import com.insightflow.agent.dto.ClassificationResult;
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
 * 游戏客服工单分类 Agent。
 *
 * <p>分类只产生规范 canonical key，无法归类时由 Prompt 约束输出 unclassified；
 * 有工作区上下文的正式调用会写入 AgentRun，直接 {@link #execute(String)} 保留给无工作区的纯单元使用。</p>
 */
@Component
@Conditional(AgentApiKeyPresentCondition.class)
public class ClassificationAnalyzer implements InsightAgent<ClassificationResult> {

    /** Spring AI 客户端由配置层统一提供，Agent 不持有密钥或供应商配置。 */
    private final LiteralChatModelCaller literalChatModelCaller;

    /** JSON 解析遵循对外 DTO 的 snake_case 契约，而非依赖模型自由文本。 */
    private final ObjectMapper objectMapper;

    /** 提示词与版本从集中目录读取，禁止在本 Agent 重复维护正文。 */
    private final OperationalPromptCatalog promptCatalog;

    /** 生命周期服务负责输入脱敏、工作区隔离和最终 Trace 存储。 */
    private final AgentRunService agentRunService;

    /** 模型名称是运行审计与成本比较维度，不能只存在于环境配置而未落库。 */
    private final String modelName;

    /** 显式注入依赖，使模型调用和审计副作用均能在测试中受控替换。 */
    public ClassificationAnalyzer(
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

    /** 返回集中定义的分类护栏。 */
    @Override
    public String systemPrompt() {
        return promptCatalog.classification().systemPrompt();
    }

    /** 版本进入日志与 AgentRun，正文改动必须在目录内提升版本。 */
    @Override
    public String promptVersion() {
        return promptCatalog.classification().version();
    }

    /** 解析目标固定为分类契约，调用方无需猜测字段。 */
    @Override
    public Class<ClassificationResult> outputSchema() {
        return ClassificationResult.class;
    }

    /** 无工作区上下文的执行入口仅用于测试或离线探针，不写入不可归属的审计记录。 */
    @Override
    public ClassificationResult execute(String userInput) {
        return execute(null, userInput);
    }

    /**
     * 执行一条可归属反馈分类；模型与解析失败均收敛为 null，并将同一失败状态写入已有 Trace。
     */
    public ClassificationResult execute(UUID workspacePublicId, String userInput) {
        long start = System.currentTimeMillis();
        AgentRun run = workspacePublicId == null ? null : agentRunService.start(
                workspacePublicId,
                new AgentRunService.StartRequest("classification", promptVersion(), modelName, "none", userInput));
        LlmMetrics.logStarted("Classification", promptVersion(), userInput);
        ChatResponse response;
        try {
            response = literalChatModelCaller.call(systemPrompt(), userInput);
        } catch (RuntimeException exception) {
            LlmMetrics.logFailure("Classification", promptVersion(), start, "model_call");
            failRun(workspacePublicId, run, start);
            return null;
        }
        LlmMetrics.log("Classification", promptVersion(), start, response);
        try {
            String content = response.getResult().getOutput().getText();
            ClassificationResult result = objectMapper.readValue(LlmMetrics.extractJson(content), outputSchema());
            succeedRun(workspacePublicId, run, content, response, start);
            return result;
        } catch (Exception exception) {
            LlmMetrics.logFailure("Classification", promptVersion(), start, "response_parse");
            failRun(workspacePublicId, run, start);
            return null;
        }
    }

    /** 成功审计只保存最终模型输出与 Usage，不保存中间推理或完整系统提示词。 */
    private void succeedRun(UUID workspacePublicId, AgentRun run, String output, ChatResponse response, long start) {
        if (run == null) {
            return;
        }
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        agentRunService.succeed(workspacePublicId, run.getPublicId(), new AgentRunService.Completion(
                output, null,
                toLong(usage == null ? null : usage.getPromptTokens()),
                toLong(usage == null ? null : usage.getCompletionTokens()),
                toLong(usage == null ? null : usage.getTotalTokens()),
                System.currentTimeMillis() - start));
    }

    /** Spring AI 1.1 返回 Integer token，审计模型使用 Long 以兼容数据库聚合。 */
    private Long toLong(Integer value) {
        return value == null ? null : value.longValue();
    }

    /** 失败审计使用统一错误码；详细异常仍只在受控服务端日志中出现。 */
    private void failRun(UUID workspacePublicId, AgentRun run, long start) {
        if (run != null) {
            agentRunService.fail(workspacePublicId, run.getPublicId(), System.currentTimeMillis() - start);
        }
    }
}
