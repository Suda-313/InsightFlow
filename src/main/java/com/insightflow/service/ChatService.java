package com.insightflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.agent.investigation.InvestigationEvidence;
import com.insightflow.agent.investigation.InvestigationPlan;
import com.insightflow.agent.investigation.InvestigationPlanner;
import com.insightflow.agent.investigation.InvestigationResult;
import com.insightflow.agent.investigation.InvestigationToolService;
import com.insightflow.entity.ChatMessage;
import com.insightflow.knowledge.KnowledgeEvidence;
import com.insightflow.knowledge.KnowledgeRetrievalResult;
import com.insightflow.knowledge.KnowledgeSearchTool;
import com.insightflow.agent.investigation.InvestigationToolType;
import com.insightflow.prompt.ChatPromptTemplate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import com.insightflow.config.AgentApiKeyPresentCondition;

/**
 * 舆情分析聊天用例：以受控调查证据和有限会话历史驱动单次模型回答。
 *
 * <p>模型没有仓储访问权，也不接收 SQL、内部主键或任意 Tool 名称；它只能消费本服务通过只读 Tool 白名单取得的
 * 工作区证据。用户消息和最终回答由 {@link ConversationService} 持久化，证据快照只写入 AgentRun 审计，
 * 不保存模型原始思维链或中间草稿。</p>
 */
@Service
@Conditional(AgentApiKeyPresentCondition.class)
public class ChatService {

    /** 审计中以该版本区分 P2 受控 Tool 调查与此前未检索的聊天调用。 */
    private static final String TOOL_RETRIEVAL_VERSION = "tool:v1+rag:v1";

    /** 日志只记录 Trace、意图、Tool、耗时和用量，不输出用户正文、样本文本或完整提示词。 */
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 模型客户端只负责生成最终文本，不能直接读取数据源或写入业务数据。 */
    private final ChatClient chatClient;

    /** 会话服务是历史读取与最终用户/助手消息持久化的唯一入口。 */
    private final ConversationService conversationService;

    /** AgentRun 服务统一记录运行生命周期，避免 Tool 或模型调用绕过 Trace。 */
    private final AgentRunService agentRunService;

    /** 规则规划器将问题映射为最小 Tool 白名单，避免由模型自由选择查询能力。 */
    private final InvestigationPlanner investigationPlanner;

    /** 只读调查服务按 Workspace 隔离产生可复核的聚合或脱敏证据。 */
    private final InvestigationToolService investigationToolService;

    private final KnowledgeSearchTool knowledgeSearchTool;

    /** JSON 仅用于将受控计划和证据快照写入审计字段，绝不序列化模型推理过程。 */
    private final ObjectMapper objectMapper;

    /** 线上聊天与离线评测共用版本化 Prompt 护栏，防止两条链路行为分叉。 */
    private final ChatPromptTemplate promptTemplate;

    /** 实际模型名称是审计和性能对比的维度，本地未配置时显式标为 unknown。 */
    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String configuredModelName = "unknown";

    /**
     * 构造器显式依赖调查边界而不依赖各数据仓储，确保聊天用例不能重新演化为静态全量数据拼接。
     */
    public ChatService(
            ChatClient chatClient,
            ConversationService conversationService,
            AgentRunService agentRunService,
            InvestigationPlanner investigationPlanner,
            InvestigationToolService investigationToolService,
            KnowledgeSearchTool knowledgeSearchTool,
            ObjectMapper objectMapper,
            ChatPromptTemplate promptTemplate) {
        this.chatClient = chatClient;
        this.conversationService = conversationService;
        this.agentRunService = agentRunService;
        this.investigationPlanner = investigationPlanner;
        this.investigationToolService = investigationToolService;
        this.knowledgeSearchTool = knowledgeSearchTool;
        this.objectMapper = objectMapper;
        this.promptTemplate = promptTemplate;
    }

    /**
     * 处理单条会话消息并保存用户问题与最终回答。
     *
     * <p>先创建运行 Trace，再执行 Tool，因而 Tool 或模型失败都会留下可追踪的失败终态；历史只作为低优先级上下文，
     * 当前问题只在 user 消息中出现一次。对外返回的 evidence 是可展示的受控索引，不含内部 ID 或思维链。</p>
     */
    public ChatReply chat(UUID workspacePublicId, UUID sessionPublicId, String message) {
        List<ChatMessage> history = conversationService.recentMessagesForModel(workspacePublicId, sessionPublicId);
        conversationService.appendUserMessage(workspacePublicId, sessionPublicId, message);
        InvestigationPlan plan = investigationPlanner.plan(message);
        var run = agentRunService.start(
                workspacePublicId,
                new AgentRunService.StartRequest(
                        "chat", promptTemplate.version(), configuredModelName, TOOL_RETRIEVAL_VERSION, message));
        long startedAtMs = System.currentTimeMillis();

        ChatResponse response;
        String finalContent;
        InvestigationResult investigation;
        KnowledgeRetrievalResult knowledge;
        try {
            log.info("Agent[Investigation] trace_id={}, status=started, intent={}, tools={}",
                    run.getPublicId(), plan.intent(), plan.tools());
            investigation = investigationToolService.investigate(workspacePublicId, message, plan);
            knowledge = knowledgeSearchTool.retrieve(workspacePublicId, message);
            log.info("Agent[Investigation] trace_id={}, status=succeeded, evidence_count={}",
                    run.getPublicId(), investigation.evidence().size());
            response = chatClient.prompt()
                    .system(buildSystemPrompt(investigation, knowledge, history))
                    .user(message)
                    .call()
                    .chatResponse();
            String content = response.getResult().getOutput().getContent();
            finalContent = content == null || content.isBlank() ? "抱歉，暂时无法回答。" : content;
        } catch (RuntimeException exception) {
            long latencyMs = System.currentTimeMillis() - startedAtMs;
            agentRunService.fail(workspacePublicId, run.getPublicId(), latencyMs);
            log.warn("LLM[Chat] trace_id={}, status=failed, latency_ms={}", run.getPublicId(), latencyMs);
            throw exception;
        }

        long latencyMs = System.currentTimeMillis() - startedAtMs;
        agentRunService.succeed(
                workspacePublicId,
                run.getPublicId(),
                toCompletion(response, finalContent, investigation, knowledge, latencyMs));
        logSuccessfulCall(run.getPublicId(), latencyMs, response);
        conversationService.appendAssistantMessage(workspacePublicId, sessionPublicId, finalContent);
        List<InvestigationEvidence> evidence = new java.util.ArrayList<>(investigation.evidence());
        knowledge.evidence().forEach(item -> evidence.add(asInvestigationEvidence(item)));
        return new ChatReply(sessionPublicId, run.getPublicId(), finalContent, evidence);
    }

    /** 模型系统提示词只由受控证据和截断历史组成，二者均明确标记边界。 */
    private String buildSystemPrompt(InvestigationResult investigation, KnowledgeRetrievalResult knowledge, List<ChatMessage> history) {
        return promptTemplate.render(investigation.renderForPrompt(), knowledge.renderForPrompt(), formatHistory(history));
    }

    /** 将受控调查快照序列化到审计；序列化失败必须终止本次运行，不能静默丢失证据。 */
    private String serializeEvidence(InvestigationResult investigation, KnowledgeRetrievalResult knowledge) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of("investigation", investigation, "knowledge", knowledge));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法写入调查证据审计快照", exception);
        }
    }

    /** 从供应商响应提取可选 Usage；缺失时保留 null，避免用字符数伪造成本。 */
    private AgentRunService.Completion toCompletion(
            ChatResponse response, String finalContent, InvestigationResult investigation, KnowledgeRetrievalResult knowledge, long latencyMs) {
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        Long promptTokens = usage == null ? null : usage.getPromptTokens();
        Long completionTokens = usage == null ? null : usage.getGenerationTokens();
        Long totalTokens = usage == null ? null : usage.getTotalTokens();
        return new AgentRunService.Completion(
                finalContent, serializeEvidence(investigation, knowledge), promptTokens, completionTokens, totalTokens, latencyMs);
    }

    private InvestigationEvidence asInvestigationEvidence(KnowledgeEvidence evidence) {
        return new InvestigationEvidence(evidence.id(), InvestigationToolType.KNOWLEDGE_SEARCH,
                evidence.title(), evidence.snippet(), true, evidence.sourceUrl());
    }

    /** 将服务商 Usage 与本地耗时一起输出；Usage 缺失时明确标识未知而不是记录为零。 */
    private void logSuccessfulCall(UUID traceId, long latencyMs, ChatResponse response) {
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage == null) {
            log.info("LLM[Chat] trace_id={}, status=succeeded, latency_ms={}, token 信息不可用", traceId, latencyMs);
            return;
        }
        log.info(
                "LLM[Chat] trace_id={}, status=succeeded, latency_ms={}, prompt_tokens={}, completion_tokens={}, total_tokens={}",
                traceId,
                latencyMs,
                usage.getPromptTokens(),
                usage.getGenerationTokens(),
                usage.getTotalTokens());
    }

    /** 历史最多十二条、每条最多一千字符，既支持连续对话又避免单条异常输入耗尽上下文。 */
    private String formatHistory(List<ChatMessage> history) {
        if (history.isEmpty()) {
            return "\n## 最近对话\n暂无历史对话。\n";
        }
        StringBuilder formatted = new StringBuilder("\n## 最近对话\n");
        history.forEach(message -> {
            String content = message.getContent();
            String capped = content.length() > 1000 ? content.substring(0, 1000) + "…" : content;
            formatted.append(message.getRole()).append(": ").append(capped).append("\n");
        });
        return formatted.toString();
    }

    /** API 只返回可展示的证据索引和最终文本，既支持用户复核，也不暴露原始推理链。 */
    public record ChatReply(UUID sessionId, UUID traceId, String content, List<InvestigationEvidence> evidence) {

        /** 防御性复制避免 Controller 或调用方篡改本次审计使用的证据集合。 */
        public ChatReply {
            evidence = List.copyOf(evidence);
        }
    }
}
