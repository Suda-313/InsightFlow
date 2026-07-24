package com.insightflow.evaluation.rag;

import com.insightflow.config.AgentApiKeyPresentCondition;
import com.insightflow.knowledge.KnowledgeRetrievalResult;
import com.insightflow.prompt.ChatPromptTemplate;
import org.springframework.ai.chat.client.ChatClient;
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

    /** 模型客户端只生成最终回答，不能直接操作知识库或其他业务数据。 */
    private final ChatClient chatClient;

    /** 复用线上版本化 Prompt，保证评测结论对应真实生产护栏。 */
    private final ChatPromptTemplate promptTemplate;

    /** 通过构造器注入，便于在非模型环境中替换为测试网关。 */
    public ChatClientRagEvaluationAnswerGateway(ChatClient chatClient, ChatPromptTemplate promptTemplate) {
        this.chatClient = chatClient;
        this.promptTemplate = promptTemplate;
    }

    /** 为每道题构建无历史、无舆情数据的最小上下文，禁止评测模型借用无关事实。 */
    @Override
    public String answer(String question, KnowledgeRetrievalResult retrieval) {
        String systemPrompt = promptTemplate.render(
                "\n## 调查计划\n本轮是企业知识库评测，仅可使用下方企业知识证据。\n",
                retrieval.renderForPrompt(),
                "\n## 最近对话\n无\n");
        String answer = chatClient.prompt().system(systemPrompt).user(question).call().content();
        return answer == null ? "" : answer;
    }
}
