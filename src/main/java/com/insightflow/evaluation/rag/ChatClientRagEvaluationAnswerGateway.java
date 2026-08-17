package com.insightflow.evaluation.rag;

import com.insightflow.config.AgentApiKeyPresentCondition;
import com.insightflow.knowledge.KnowledgeRetrievalResult;
import com.insightflow.prompt.ChatPromptTemplate;
import com.insightflow.prompt.LiteralChatModelCaller;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * 线上模型的 RAG 评测适配器。
 *
 * <p>使用与聊天相同的提示词护栏和已检索证据，但不创建会话、不保存用户消息，也不把评测
 * 问题混入业务对话；评测运行器最终只持久化规则评分结果。</p>
 */
@Component
@Conditional(AgentApiKeyPresentCondition.class)
public class ChatClientRagEvaluationAnswerGateway implements RagEvaluationAnswerGateway {

    /** 字面量调用器，避免知识 chunk 中的 {@code {版本号}} 被 ChatClient 模板解析。 */
    private final LiteralChatModelCaller literalChatModelCaller;

    /** 复用线上版本化 Prompt，保证评测结论对应真实生产护栏。 */
    private final ChatPromptTemplate promptTemplate;

    /** 通过构造器注入，便于在非模型环境中替换为测试网关。 */
    public ChatClientRagEvaluationAnswerGateway(
            LiteralChatModelCaller literalChatModelCaller, ChatPromptTemplate promptTemplate) {
        this.literalChatModelCaller = literalChatModelCaller;
        this.promptTemplate = promptTemplate;
    }

    /** 为每道题构建无历史、无舆情数据的最小上下文，禁止评测模型借用无关事实。 */
    @Override
    public RagEvaluationGenerationResult answer(String question, KnowledgeRetrievalResult retrieval) {
        String systemPrompt = promptTemplate.render(
                "\n## 调查计划\n本轮是企业知识库评测，仅可使用下方企业知识证据。\n",
                retrieval.renderForPrompt(),
                "\n## 最近对话\n无\n");
        ChatResponse response = literalChatModelCaller.call(systemPrompt, question);
        String content = "";
        if (response.getResult() != null && response.getResult().getOutput() != null) {
            String raw = response.getResult().getOutput().getText();
            content = raw == null ? "" : raw;
        }
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage == null) {
            return new RagEvaluationGenerationResult(content, null, null, null);
        }
        return new RagEvaluationGenerationResult(
                content, toLong(usage.getPromptTokens()), toLong(usage.getCompletionTokens()), toLong(usage.getTotalTokens()));
    }

    /** Spring AI 1.1 返回 Integer token，评估结果使用 Long 便于汇总。 */
    private Long toLong(Integer value) {
        return value == null ? null : value.longValue();
    }
}
