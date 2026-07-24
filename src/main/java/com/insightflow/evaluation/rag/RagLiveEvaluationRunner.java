package com.insightflow.evaluation.rag;

import com.insightflow.knowledge.KnowledgeRetrievalResult;
import com.insightflow.knowledge.KnowledgeSearchTool;
import com.insightflow.prompt.ChatPromptTemplate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import com.insightflow.config.AgentApiKeyPresentCondition;

/**
 * 将动态金标、线上同款受控检索与确定性规则评分串成一次 RAG 评测。
 *
 * <p>此运行器不允许模型决定检索参数、执行 SQL 或继续调用工具；每题固定执行一次
 * {@link KnowledgeSearchTool}，回答中的引用仅用正则提取后与实际检索证据对照。</p>
 */
@Service
@Conditional(AgentApiKeyPresentCondition.class)
public class RagLiveEvaluationRunner {

    /** 只接受形如 [证据: knowledge:...] 的引用，非知识引用不计入本专项指标。 */
    private static final Pattern KNOWLEDGE_CITATION = Pattern.compile("\\[证据:\\s*(knowledge:[^\\]\\s]+)\\]");

    /** 固定题集工厂负责组织与 Workspace 可见范围，不让评测运行器自行读库。 */
    private final RagEvaluationFixtureFactory fixtureFactory;

    /** 线上与评测复用同一受控检索 Tool，确保两者的过滤和两轮上限一致。 */
    private final KnowledgeSearchTool knowledgeSearchTool;

    /** 模型回答通过窄接口隔离，运行器不持有 ChatClient 或提示词拼接能力。 */
    private final RagEvaluationAnswerGateway answerGateway;

    /** Prompt 版本来自线上同一模板，支持将结果与聊天运行审计关联。 */
    private final ChatPromptTemplate promptTemplate = new ChatPromptTemplate();

    /** 评测历史中的模型名不能从客户端传入，避免伪造比较维度。 */
    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String configuredModelName = "unknown";

    /** 显式注入三个受控边界，便于以替身验证运行器不绕过检索或模型入口。 */
    public RagLiveEvaluationRunner(
            RagEvaluationFixtureFactory fixtureFactory,
            KnowledgeSearchTool knowledgeSearchTool,
            RagEvaluationAnswerGateway answerGateway) {
        this.fixtureFactory = fixtureFactory;
        this.knowledgeSearchTool = knowledgeSearchTool;
        this.answerGateway = answerGateway;
    }

    /**
     * 运行当前 Workspace 的全部固定题。
     *
     * <p>单题模型调用失败时保留失败计数并继续后续题目，避免一次供应商波动使已有评测
     * 历史完全丢失；失败的有依据题会被保守地视为未能给出可靠答案。</p>
     */
    public RagEvaluationRunResult run(UUID workspacePublicId) {
        RagEvaluationFixture fixture = fixtureFactory.create(workspacePublicId);
        Map<String, RagEvaluationObservation> observations = new LinkedHashMap<>();
        Map<String, String> statuses = new LinkedHashMap<>();
        for (RagEvaluationCaseDefinition evaluationCase : fixture.cases()) {
            try {
                KnowledgeRetrievalResult retrieval = knowledgeSearchTool.retrieve(workspacePublicId, evaluationCase.question());
                String answer = answerGateway.answer(evaluationCase.question(), retrieval);
                observations.put(evaluationCase.caseId(), observation(retrieval, answer));
                statuses.put(evaluationCase.caseId(), "succeeded");
            } catch (RuntimeException exception) {
                observations.put(evaluationCase.caseId(), new RagEvaluationObservation(Set.of(), Set.of(),
                        !evaluationCase.expectedEvidencePrefixes().isEmpty()));
                statuses.put(evaluationCase.caseId(), "failed");
            }
        }

        List<RagGoldEvaluationCase> goldCases = fixture.cases().stream()
                .map(item -> new RagGoldEvaluationCase(item.caseId(), item.expectedEvidencePrefixes()))
                .toList();
        RagEvaluationMetrics metrics = new RagGoldEvaluationRunner().run(
                goldCases, evaluationCase -> observations.get(evaluationCase.caseId()));
        List<RagEvaluationCaseResult> caseResults = new ArrayList<>();
        fixture.cases().forEach(evaluationCase -> caseResults.add(toCaseResult(
                evaluationCase, observations.get(evaluationCase.caseId()), statuses.get(evaluationCase.caseId()))));
        return new RagEvaluationRunResult(
                fixture.datasetVersion(), promptTemplate.version(), configuredModelName, "knowledge:rrf:v1", metrics, caseResults);
    }

    /** 将真实检索证据和回答引用压缩为确定性评测观测，不保留回答正文。 */
    private RagEvaluationObservation observation(KnowledgeRetrievalResult retrieval, String answer) {
        Set<String> retrieved = retrieval.evidence().stream().map(item -> item.id()).collect(java.util.stream.Collectors.toSet());
        Set<String> cited = citations(answer == null ? "" : answer);
        boolean containsKnowledgeClaim = answer == null || !answer.contains("未检索到已发布企业知识");
        return new RagEvaluationObservation(retrieved, cited, containsKnowledgeClaim);
    }

    /** 只提取知识证据标记，避免普通 Markdown 文本或数据调查证据污染 RAG 引用指标。 */
    private Set<String> citations(String answer) {
        Matcher matcher = KNOWLEDGE_CITATION.matcher(answer);
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return Set.copyOf(result);
    }

    /** 逐题结果复用与总指标相同的前缀和集合规则，避免页面与后台统计口径漂移。 */
    private RagEvaluationCaseResult toCaseResult(
            RagEvaluationCaseDefinition evaluationCase, RagEvaluationObservation observation, String status) {
        int retrievedExpected = (int) evaluationCase.expectedEvidencePrefixes().stream()
                .filter(prefix -> observation.retrievedEvidenceIds().stream().anyMatch(id -> id.startsWith(prefix)))
                .count();
        int correctCitation = (int) observation.citedEvidenceIds().stream()
                .filter(observation.retrievedEvidenceIds()::contains)
                .count();
        boolean ungrounded = observation.containsKnowledgeClaim()
                && (observation.citedEvidenceIds().isEmpty()
                || correctCitation != observation.citedEvidenceIds().size());
        return new RagEvaluationCaseResult(
                evaluationCase.caseId(), evaluationCase.category(), status,
                evaluationCase.expectedEvidencePrefixes().size(), retrievedExpected,
                observation.citedEvidenceIds().size(), correctCitation, ungrounded);
    }
}
