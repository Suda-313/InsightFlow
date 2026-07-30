package com.insightflow.evaluation;

import com.insightflow.agent.LlmMetrics;
import com.insightflow.config.AgentApiKeyPresentCondition;
import com.insightflow.prompt.ChatPromptTemplate;
import com.insightflow.prompt.LiteralChatModelCaller;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

/**
 * 固定金标集的离线评测运行器。
 *
 * <p>它直接使用版本化 Prompt 与固定脱敏 fixture 调用模型，不经 ChatService，
 * 因此不会写入真实用户会话、AgentRun 或任何 workspace 业务数据。</p>
 */
@Service
@Conditional(AgentApiKeyPresentCondition.class)
public class GoldEvaluationRunner {

    /** 只记录题目与版本等非敏感可观测字段，不记录固定答案正文或上游异常文本。 */
    private static final Logger log = LoggerFactory.getLogger(GoldEvaluationRunner.class);

    /** 字面量模型调用，fixture 与题目可含大括号占位符。 */
    private final LiteralChatModelCaller literalChatModelCaller;
    /** 金标题目来源，负责保证题量和类别契约。 */
    private final GoldEvaluationDatasetLoader datasetLoader;
    /** 固定脱敏数据来源，禁止评测退回到真实工作区。 */
    private final EvaluationFixtureLoader fixtureLoader;
    /** 线上聊天和评测共用的系统提示词版本与护栏。 */
    private final ChatPromptTemplate promptTemplate;
    /** 确定性规则评分器，不把第二个模型引入评测判定。 */
    private final EvaluationCaseScorer scorer;
    /** 实际模型名是结果比较维度，不能只依赖配置文件外部约定。 */
    private final String modelName;

    /** 通过构造器注入，使运行器在测试中可用同样的调用边界替换外部模型。 */
    public GoldEvaluationRunner(
            LiteralChatModelCaller literalChatModelCaller,
            GoldEvaluationDatasetLoader datasetLoader,
            EvaluationFixtureLoader fixtureLoader,
            ChatPromptTemplate promptTemplate,
            EvaluationCaseScorer scorer,
            @Value("${spring.ai.openai.chat.options.model:unknown}") String modelName) {
        this.literalChatModelCaller = literalChatModelCaller;
        this.datasetLoader = datasetLoader;
        this.fixtureLoader = fixtureLoader;
        this.promptTemplate = promptTemplate;
        this.scorer = scorer;
        this.modelName = modelName;
    }

    /**
     * 依次运行完整金标集；单题失败会被收敛为受控结果，避免一次服务商抖动丢失整批基线。
     */
    public GoldEvaluationRunResult run() {
        GoldEvaluationDataset dataset = datasetLoader.load();
        List<EvaluationCaseRunResult> results = new ArrayList<>();
        for (GoldEvaluationCase evaluationCase : dataset.cases()) {
            results.add(runCase(evaluationCase));
        }
        return new GoldEvaluationRunResult(
                dataset.version(), promptTemplate.version(), modelName, results, summarize(results));
    }

    /**
     * 运行一条题目并保留固定失败阶段；日志仅关联 caseId，避免将题目或模型正文输出到服务端日志。
     */
    private EvaluationCaseRunResult runCase(GoldEvaluationCase evaluationCase) {
        long startedAtMs = System.currentTimeMillis();
        try {
            String fixtureContext = wrapFixtureAsEvidence(evaluationCase.fixtureId(), fixtureLoader.load(evaluationCase.fixtureId()));
            log.info("Gold evaluation case started: case_id={}, category={}, prompt_version={}",
                    evaluationCase.caseId(), evaluationCase.category(), promptTemplate.version());
            LlmMetrics.logStarted("Evaluation", evaluationCase.question());
            ChatResponse response = literalChatModelCaller.call(
                    promptTemplate.render(fixtureContext, "\n## 最近对话\n暂无历史对话。\n"),
                    evaluationCase.question());
            long latencyMs = System.currentTimeMillis() - startedAtMs;
            LlmMetrics.log("Evaluation", startedAtMs, response);
            String output = response.getResult().getOutput().getContent();
            EvaluationCaseScore score = scorer.score(evaluationCase, output);
            Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            log.info("Gold evaluation case completed: case_id={}, fact_coverage={}/{}, forbidden_hits={}, evidence_citation={}",
                    evaluationCase.caseId(), score.coveredRequiredFactCount(), score.requiredFactCount(),
                    score.hitForbiddenClaimCount(), score.evidenceCitationPresent());
            return new EvaluationCaseRunResult(
                    evaluationCase.caseId(), evaluationCase.category(), "succeeded", score, output, latencyMs,
                    usage == null ? null : usage.getPromptTokens(),
                    usage == null ? null : usage.getGenerationTokens(),
                    usage == null ? null : usage.getTotalTokens(), null);
        } catch (RuntimeException exception) {
            long latencyMs = System.currentTimeMillis() - startedAtMs;
            log.warn("Gold evaluation case failed: case_id={}, exception_type={}",
                    evaluationCase.caseId(), exception.getClass().getSimpleName());
            return new EvaluationCaseRunResult(
                    evaluationCase.caseId(), evaluationCase.category(), "failed", null, null, latencyMs,
                    null, null, null, "model_or_fixture");
        }
    }

    /**
     * 汇总规则质量、耗时与 Usage；Token 未返回时保持 null，绝不以估算值伪造成本指标。
     */
    private GoldEvaluationMetrics summarize(List<EvaluationCaseRunResult> results) {
        List<EvaluationCaseRunResult> succeeded = results.stream()
                .filter(result -> "succeeded".equals(result.status()))
                .toList();
        int requiredFacts = succeeded.stream().mapToInt(result -> result.score().requiredFactCount()).sum();
        int coveredFacts = succeeded.stream().mapToInt(result -> result.score().coveredRequiredFactCount()).sum();
        int forbiddenClaims = succeeded.stream().mapToInt(result -> result.score().forbiddenClaimCount()).sum();
        int hitForbiddenClaims = succeeded.stream().mapToInt(result -> result.score().hitForbiddenClaimCount()).sum();
        long specificAnswers = succeeded.stream().filter(result -> result.score().answerSpecific()).count();
        long citedAnswers = succeeded.stream().filter(result -> result.score().evidenceCitationPresent()).count();
        List<EvaluationCaseRunResult> refusalCases = succeeded.stream()
                .filter(result -> result.category().equals("refusal"))
                .toList();
        long compliantRefusals = refusalCases.stream().filter(result -> result.score().refusalCompliant()).count();
        return new GoldEvaluationMetrics(
                results.size(),
                succeeded.size(),
                results.size() - succeeded.size(),
                ratio(coveredFacts, requiredFacts),
                ratio(hitForbiddenClaims, forbiddenClaims),
                refusalCases.isEmpty() ? null : ratio(compliantRefusals, refusalCases.size()),
                succeeded.stream().map(EvaluationCaseRunResult::latencyMs).mapToLong(Long::longValue).sum(),
                sumIfPresent(succeeded.stream().map(EvaluationCaseRunResult::promptTokens).toList()),
                sumIfPresent(succeeded.stream().map(EvaluationCaseRunResult::completionTokens).toList()),
                sumIfPresent(succeeded.stream().map(EvaluationCaseRunResult::totalTokens).toList()),
                percentileIfPresent(succeeded.stream().map(EvaluationCaseRunResult::latencyMs).toList(), 0.50),
                percentileIfPresent(succeeded.stream().map(EvaluationCaseRunResult::latencyMs).toList(), 0.95),
                percentileIfPresent(succeeded.stream().map(EvaluationCaseRunResult::promptTokens).toList(), 0.50),
                percentileIfPresent(succeeded.stream().map(EvaluationCaseRunResult::promptTokens).toList(), 0.95),
                percentileIfPresent(succeeded.stream().map(EvaluationCaseRunResult::completionTokens).toList(), 0.50),
                percentileIfPresent(succeeded.stream().map(EvaluationCaseRunResult::completionTokens).toList(), 0.95),
                ratio(specificAnswers, succeeded.size()),
                ratio(citedAnswers, succeeded.size()));
    }

    /** 将固定 fixture 包装为可引用证据，确保离线评测与线上 P2 Prompt 共用同一引用契约。 */
    private String wrapFixtureAsEvidence(String fixtureId, String fixture) {
        return "\n## 调查计划\n意图：固定金标评测\n已调用 Tool：[FIXTURE]\n\n## 证据索引\n- [fixture:"
                + fixtureId + "] 固定脱敏评测证据：" + fixture;
    }

    /** 安全计算比例，分母为零时不制造 NaN 或无穷值。 */
    private double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    /** 只有所有成功题目都返回相应 Usage 时才给出累计值，避免把部分成本误当成整批成本。 */
    private Long sumIfPresent(List<Long> values) {
        return values.stream().anyMatch(value -> value == null)
                ? null
                : values.stream().mapToLong(Long::longValue).sum();
    }

    /**
     * 使用 nearest-rank 口径计算离散样本分位数；任一成功题缺少同类 Usage 时返回 null，
     * 以免将部分样本误报为整批评测的性能或成本基线。
     */
    private Long percentileIfPresent(List<Long> values, double percentile) {
        if (values.isEmpty() || values.stream().anyMatch(value -> value == null)) {
            return null;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(index);
    }
}
