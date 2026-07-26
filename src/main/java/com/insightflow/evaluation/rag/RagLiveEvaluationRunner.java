package com.insightflow.evaluation.rag;

import com.insightflow.config.AgentApiKeyPresentCondition;
import com.insightflow.knowledge.KnowledgeRetrievalResult;
import com.insightflow.prompt.ChatPromptTemplate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

/**
 * 将动态金标、受控单题执行和确定性规则评分串成一次 RAG 评测。
 *
 * <p>运行器不直接持有 ChatClient、嵌入模型或仓储；每题只能委托给带超时的执行器。
 * 这保证模型阻塞被限制在单题范围，并让评分规则始终只消费受控检索证据和最终回答。</p>
 */
@Service
@Conditional(AgentApiKeyPresentCondition.class)
public class RagLiveEvaluationRunner {

    /** 只接受形如 [证据: knowledge:...] 的引用，普通 Markdown 文字不计入 RAG 引用指标。*/
    private static final Pattern KNOWLEDGE_CITATION = Pattern.compile("\\[证据:\\s*(knowledge:[^\\]\\s]+)\\]");

    /** 逐题日志只含题目 ID、阶段、耗时与真实 Usage，绝不输出问题、回答或证据正文。*/
    private static final Logger log = LoggerFactory.getLogger(RagLiveEvaluationRunner.class);

    /** 题集工厂按当前 Workspace 的可见发布文档生成固定、可复现的金标题目。*/
    private final RagEvaluationFixtureFactory fixtureFactory;

    /** 单题执行器承接受控检索、模型调用、超时与异常收敛，运行器只负责评分。*/
    private final RagEvaluationCaseExecutor caseExecutor;

    /** 评测历史中的 Prompt 版本必须与线上受控模板一致，不能从浏览器传入。*/
    private final ChatPromptTemplate promptTemplate = new ChatPromptTemplate();

    /** 供应商模型名是可比较的运行维度；本地未启用模型时使用 unknown。*/
    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String configuredModelName = "unknown";

    /**
     * 构造器只注入题集与单题边界，避免评分器绕过超时、日志或 Workspace 约束。
     */
    public RagLiveEvaluationRunner(RagEvaluationFixtureFactory fixtureFactory, RagEvaluationCaseExecutor caseExecutor) {
        this.fixtureFactory = fixtureFactory;
        this.caseExecutor = caseExecutor;
    }

    /**
     * 运行当前 Workspace 的全部固定题目。
     *
     * <p>一题失败时用空观察结果保守评分，但循环不会提前结束；这让最终历史明确反映失败题，
     * 又不会因为单个供应商波动完全丢失同批已完成题目的基线价值。</p>
     */
    public RagEvaluationRunResult run(UUID workspacePublicId) {
        RagEvaluationFixture fixture = fixtureFactory.create(workspacePublicId);
        Map<String, RagEvaluationObservation> observations = new LinkedHashMap<>();
        Map<String, String> statuses = new LinkedHashMap<>();
        for (RagEvaluationCaseDefinition evaluationCase : fixture.cases()) {
            log.info("RAG_EVAL case_id={}, status=started", evaluationCase.caseId());
            RagEvaluationCaseExecution execution = caseExecutor.execute(workspacePublicId, evaluationCase);
            RagEvaluationObservation observation = "succeeded".equals(execution.status())
                    ? observation(execution.retrieval(), execution.answer())
                    : new RagEvaluationObservation(Set.of(), Set.of(), !evaluationCase.expectedEvidencePrefixes().isEmpty());
            observations.put(evaluationCase.caseId(), observation);
            statuses.put(evaluationCase.caseId(), execution.status());
            logCaseCompletion(evaluationCase.caseId(), execution);
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

    /**
     * 输出可审计的逐题终态；当前回答网关尚未返回 Usage 时明确标记 unavailable，禁止用字符数估算 Token。
     */
    private void logCaseCompletion(String caseId, RagEvaluationCaseExecution execution) {
        log.info(
                "RAG_EVAL case_id={}, status={}, failure_stage={}, retrieval_latency_ms={}, generation_latency_ms={}, total_latency_ms={}, prompt_tokens=unavailable, completion_tokens=unavailable, total_tokens=unavailable",
                caseId,
                execution.status(),
                execution.failureStage(),
                execution.retrievalLatencyMs(),
                execution.generationLatencyMs(),
                execution.totalLatencyMs());
    }

    /** 将真实检索证据和回答引用压缩为确定性观察，不保留模型答案正文。*/
    private RagEvaluationObservation observation(KnowledgeRetrievalResult retrieval, String answer) {
        Set<String> retrieved = retrieval.evidence().stream().map(item -> item.id()).collect(java.util.stream.Collectors.toSet());
        Set<String> cited = citations(answer == null ? "" : answer);
        boolean containsKnowledgeClaim = answer == null || !answer.contains("未检索到已发布企业知识");
        return new RagEvaluationObservation(retrieved, cited, containsKnowledgeClaim);
    }

    /** 只提取知识证据标记，避免普通文本或数据调查证据污染 RAG 引用指标。*/
    private Set<String> citations(String answer) {
        Matcher matcher = KNOWLEDGE_CITATION.matcher(answer);
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return Set.copyOf(result);
    }

    /** 使用与总指标相同的前缀和集合规则，防止页面逐题展示与后端聚合口径漂移。*/
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
