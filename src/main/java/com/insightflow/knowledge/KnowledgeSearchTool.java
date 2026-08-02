package com.insightflow.knowledge;

import com.insightflow.entity.KnowledgeDocumentType;
import com.insightflow.entity.Workspace;
import com.insightflow.service.WorkspaceService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 单 Agent 的只读企业知识检索工具，最多允许一次受控补检索。
 *
 * <p>调用方只能提供当前 Workspace 和自然语言问题；组织范围、可见范围、文档类型与 SQL
 * 均由服务端固定，模型无法借此读取其他游戏、未发布文档或任意数据库数据。</p>
 */
@Component
public class KnowledgeSearchTool {

    /** 固定最多返回八条证据，避免知识片段挤占业务数据与会话上下文。 */
    private static final int MAX_EVIDENCE_COUNT = 8;

    /** Workspace 服务负责把外部 UUID 解析为可信的内部隔离键。 */
    private final WorkspaceService workspaces;

    /** 仅嵌入用户问题，原始知识文档只在受控发布流程中嵌入。 */
    private final KnowledgeEmbeddingGateway embeddings;

    /** 向量仓储封装固定的 FTS、pgvector 和范围过滤 SQL。 */
    private final KnowledgeVectorStore vectors;

    /** 首轮类型计划来自确定性业务关键词，不接受模型自由生成的检索参数。 */
    private final KnowledgeRetrievalPlanner planner;

    /** 证据不足时才允许补检索一次，阻止 ReAct 式无限循环。 */
    private final KnowledgeEvidenceGuardrail evidenceGuardrail;

    /** 词法查询扩展只影响 FTS，不改变 embedding 输入。 */
    private final KnowledgeQueryExpander queryExpander;

    /** RRF 与 Cross-encoder 精排选择器；默认 RRF-only。 */
    private final KnowledgeRerankerSelector rerankerSelector;

    /** CROSS/VERSION 问题拆分子查询后再合并候选。 */
    private final KnowledgeCrossQueryDecomposer crossQueryDecomposer;

    /** P2：精排前标题/实体加权与双主体覆盖保护。 */
    private final KnowledgeTitleEntityScoreBooster titleEntityScoreBooster;

    /** P3：Top8 覆盖感知贪心选择。 */
    private final KnowledgeCoverageAwareSelector coverageAwareSelector;

    /** Phase 2：标识符缺失时补召回进 Candidate 池。 */
    private final KnowledgeIdentifierCandidateSupplement identifierCandidateSupplement;

    /** Phase 3：多路子查询 Top8 最低配额，再交覆盖贪心。 */
    private final KnowledgeSubQueryQuotaEnforcer subQueryQuotaEnforcer;

    /** Small-to-big：命中 chunk 展示前扩展同 section 上下文，不改 Recall 口径。 */
    private final KnowledgeEvidenceContextExpander evidenceContextExpander;

    /** 所有依赖均由 Spring 注入，便于单测替换模型和数据库边界。 */
    @Autowired
    public KnowledgeSearchTool(
            WorkspaceService workspaces,
            KnowledgeEmbeddingGateway embeddings,
            KnowledgeVectorStore vectors,
            KnowledgeRetrievalPlanner planner,
            KnowledgeEvidenceGuardrail evidenceGuardrail,
            KnowledgeQueryExpander queryExpander,
            KnowledgeRerankerSelector rerankerSelector,
            KnowledgeCrossQueryDecomposer crossQueryDecomposer,
            KnowledgeTitleEntityScoreBooster titleEntityScoreBooster,
            KnowledgeCoverageAwareSelector coverageAwareSelector,
            KnowledgeIdentifierCandidateSupplement identifierCandidateSupplement,
            KnowledgeSubQueryQuotaEnforcer subQueryQuotaEnforcer,
            KnowledgeEvidenceContextExpander evidenceContextExpander) {
        this.workspaces = workspaces;
        this.embeddings = embeddings;
        this.vectors = vectors;
        this.planner = planner;
        this.evidenceGuardrail = evidenceGuardrail;
        this.queryExpander = queryExpander;
        this.rerankerSelector = rerankerSelector;
        this.crossQueryDecomposer = crossQueryDecomposer;
        this.titleEntityScoreBooster = titleEntityScoreBooster;
        this.coverageAwareSelector = coverageAwareSelector;
        this.identifierCandidateSupplement = identifierCandidateSupplement;
        this.subQueryQuotaEnforcer = subQueryQuotaEnforcer;
        this.evidenceContextExpander = evidenceContextExpander;
    }

    /** 单测兼容构造器：默认用向量仓储构造 expander。 */
    KnowledgeSearchTool(
            WorkspaceService workspaces,
            KnowledgeEmbeddingGateway embeddings,
            KnowledgeVectorStore vectors,
            KnowledgeRetrievalPlanner planner,
            KnowledgeEvidenceGuardrail evidenceGuardrail,
            KnowledgeQueryExpander queryExpander,
            KnowledgeRerankerSelector rerankerSelector,
            KnowledgeCrossQueryDecomposer crossQueryDecomposer,
            KnowledgeTitleEntityScoreBooster titleEntityScoreBooster,
            KnowledgeCoverageAwareSelector coverageAwareSelector,
            KnowledgeIdentifierCandidateSupplement identifierCandidateSupplement,
            KnowledgeSubQueryQuotaEnforcer subQueryQuotaEnforcer) {
        this(
                workspaces,
                embeddings,
                vectors,
                planner,
                evidenceGuardrail,
                queryExpander,
                rerankerSelector,
                crossQueryDecomposer,
                titleEntityScoreBooster,
                coverageAwareSelector,
                identifierCandidateSupplement,
                subQueryQuotaEnforcer,
                new KnowledgeEvidenceContextExpander(vectors));
    }

    /**
     * 首轮按问题类型检索；只有首轮已被收窄且证据不足时，才放宽类型补检索一次。
     * 即使补检索无结果也立即返回，绝不由模型决定继续调用次数。
     */
    public KnowledgeRetrievalResult retrieve(UUID workspacePublicId, String question) {
        return retrieveWithDiagnostics(workspacePublicId, question).result();
    }

    /** 允许评测层注入已缓存的 query embedding，避免重复调用嵌入 API。 */
    public KnowledgeRetrievalDiagnostics retrieveWithDiagnostics(
            UUID workspacePublicId, String question, List<Double> queryEmbedding) {
        return retrieveWithDiagnostics(workspacePublicId, question, queryEmbedding, null);
    }

    /**
     * 带精排覆盖项的检索：评测 CLI 可显式开启 Cross-encoder 而不改全局配置。
     */
    public KnowledgeRetrievalDiagnostics retrieveWithDiagnostics(
            UUID workspacePublicId,
            String question,
            List<Double> queryEmbedding,
            KnowledgeRetrievalOptions retrievalOptions) {
        Workspace workspace = workspaces.get(workspacePublicId);
        KnowledgeRetrievalOptions effectiveOptions = retrievalOptions == null
                ? KnowledgeRetrievalOptions.defaults()
                : retrievalOptions;
        List<String> subQueries = resolveSubQueries(question, effectiveOptions);
        List<KnowledgeSearchResult> subResults = new ArrayList<>(subQueries.size());
        List<Integer> candidatesPerSubQuery = new ArrayList<>(subQueries.size());
        int rounds = 0;

        for (int index = 0; index < subQueries.size(); index++) {
            String subQuery = subQueries.get(index);
            List<Double> subEmbedding = resolveSubQueryEmbedding(
                    question, queryEmbedding, subQueries, index, subQuery);
            SubQuerySearchOutcome subOutcome = searchSingleQuery(workspace, subQuery, subEmbedding);
            KnowledgeSearchResult subResult = subOutcome.result();
            if (effectiveOptions.identifierSupplementEnabled()) {
                subResult = identifierCandidateSupplement.supplement(workspace, subQuery, subResult);
            }
            subResults.add(subResult);
            candidatesPerSubQuery.add(subResult.candidates().size());
            rounds = Math.max(rounds, subOutcome.rounds());
        }

        KnowledgeSearchResult effective = subResults.size() == 1
                ? subResults.get(0)
                : KnowledgeSubQueryCandidateMerger.merge(
                        subResults, KnowledgeSearchOptions.rrfV2("").candidateLimit());

        if (effectiveOptions.identifierSupplementEnabled() && subResults.size() > 1) {
            effective = identifierCandidateSupplement.supplement(workspace, question, effective);
        }

        List<KnowledgeVectorStore.SearchCandidate> boostedCandidates = titleEntityScoreBooster.apply(
                question, effective.candidates(), effectiveOptions);

        int selectionPoolSize = boostedCandidates.size();
        KnowledgeRerankOutcome rerankOutcome = rerankerSelector.rerank(
                question, boostedCandidates, selectionPoolSize, effectiveOptions);
        List<KnowledgeVectorStore.SearchCandidate> finalCandidates = effectiveOptions.subQueryQuotaEnabled()
                ? subQueryQuotaEnforcer.selectTopEvidence(
                        question,
                        rerankOutcome.rankedCandidates(),
                        MAX_EVIDENCE_COUNT,
                        effectiveOptions,
                        subResults,
                        coverageAwareSelector)
                : coverageAwareSelector.select(
                        question,
                        rerankOutcome.rankedCandidates(),
                        MAX_EVIDENCE_COUNT,
                        effectiveOptions);
        rerankOutcome = rerankOutcome.withRankedCandidates(finalCandidates);
        boolean rerankScoresActive = rerankOutcome != null
                && "cross-encoder".equals(rerankOutcome.rerankerName())
                && !rerankOutcome.fallbackUsed();
        boolean forceAbstain = evidenceGuardrail.shouldForceAbstain(question, effectiveOptions.questionTypeName());
        KnowledgeEvidenceGateDecision gateDecision = effectiveOptions.evidenceGateEnabled()
                ? evidenceGuardrail.decide(finalCandidates, rerankScoresActive, forceAbstain)
                : KnowledgeEvidenceGateDecision.injectAll(finalCandidates);
        List<KnowledgeVectorStore.SearchCandidate> injected = gateDecision.injected();
        Map<UUID, String> expandedSnippets = evidenceContextExpander.expandBatch(
                workspace.getOrganizationId(), workspace.getId(), injected);
        List<KnowledgeEvidence> evidence = injected.stream()
                .map(candidate -> toEvidence(workspacePublicId, candidate, expandedSnippets))
                .toList();
        KnowledgeRetrievalResult result = new KnowledgeRetrievalResult(
                rounds, evidence, gateDecision.outcome(), gateDecision.inputCount(), gateDecision.topScore());
        return new KnowledgeRetrievalDiagnostics(
                result,
                boostedCandidates,
                effective.lexicalOnlyChunkIds(),
                effective.vectorOnlyChunkIds(),
                effective.bothSourceChunkIds(),
                rerankOutcome,
                subQueries,
                candidatesPerSubQuery);
    }

    /**
     * 带候选诊断的检索：RRF Top50 候选，精排后返回 Top8 证据给生成模型。
     * 评测漏斗复用本方法，避免复制检索逻辑。
     */
    public KnowledgeRetrievalDiagnostics retrieveWithDiagnostics(UUID workspacePublicId, String question) {
        List<Double> queryEmbedding = embeddings.embed(List.of(question)).get(0);
        return retrieveWithDiagnostics(workspacePublicId, question, queryEmbedding, null);
    }

    /** 解析本请求的检索版本标签，供 AgentRun / RAG 评测元数据写入。 */
    public String resolveRetrievalVersionLabel(KnowledgeRetrievalOptions retrievalOptions) {
        KnowledgeRetrievalOptions effective = retrievalOptions == null
                ? KnowledgeRetrievalOptions.defaults()
                : retrievalOptions;
        StringBuilder label = new StringBuilder(rerankerSelector.resolveRetrievalVersionLabel(effective));
        label.append("+entity+coverage");
        if (effective.identifierSupplementEnabled()) {
            label.append("+identifier");
        }
        if (effective.subQueryQuotaEnabled()) {
            label.append("+subquota+precise+upgrade+anchor");
        }
        if (effective.evidenceGateEnabled()) {
            label.append("+gate");
        }
        label.append("+small2big");
        return label.toString();
    }

    private List<String> resolveSubQueries(String question, KnowledgeRetrievalOptions retrievalOptions) {
        if (retrievalOptions.subQueries() != null && !retrievalOptions.subQueries().isEmpty()) {
            return retrievalOptions.subQueries();
        }
        List<String> decomposed = crossQueryDecomposer.decompose(question, retrievalOptions.questionTypeName());
        return decomposed.isEmpty() ? List.of(question) : decomposed;
    }

    private List<Double> resolveSubQueryEmbedding(
            String originalQuestion,
            List<Double> queryEmbedding,
            List<String> subQueries,
            int index,
            String subQuery) {
        if (subQueries.size() == 1 && queryEmbedding != null && !queryEmbedding.isEmpty()) {
            return queryEmbedding;
        }
        if (subQueries.size() > 1 && originalQuestion.equals(subQuery) && queryEmbedding != null && !queryEmbedding.isEmpty()) {
            return queryEmbedding;
        }
        return embeddings.embed(List.of(subQuery)).get(0);
    }

    /** 单路子查询：Planner 收窄 + 可选全类型补检索，与分解前行为一致。 */
    private SubQuerySearchOutcome searchSingleQuery(
            Workspace workspace, String question, List<Double> queryEmbedding) {
        String expandedQuery = queryExpander.expand(question);
        KnowledgeSearchOptions options = KnowledgeSearchOptions.rrfV2(expandedQuery);

        List<KnowledgeDocumentType> firstRoundTypes = planner.plan(question);
        KnowledgeSearchResult firstRound = vectors.searchWithOptions(
                workspace.getOrganizationId(), workspace.getId(), question, firstRoundTypes,
                queryEmbedding, options);

        if (!firstRoundTypes.isEmpty()) {
            KnowledgeSearchResult broadRound = vectors.searchWithOptions(
                    workspace.getOrganizationId(),
                    workspace.getId(),
                    question,
                    List.of(),
                    queryEmbedding,
                    options);
            return new SubQuerySearchOutcome(
                    KnowledgeSearchResultMerger.merge(firstRound, broadRound, options.candidateLimit()), 2);
        }
        return new SubQuerySearchOutcome(firstRound, 1);
    }

    private record SubQuerySearchOutcome(KnowledgeSearchResult result, int rounds) {}

    private KnowledgeEvidence toEvidence(
            UUID workspacePublicId,
            KnowledgeVectorStore.SearchCandidate candidate,
            Map<UUID, String> expandedSnippets) {
        String snippet = expandedSnippets.getOrDefault(
                candidate.chunkId(),
                KnowledgeEvidenceContextExpander.expandForHit(candidate, List.of()));
        return new KnowledgeEvidence(
                "knowledge:" + candidate.documentId() + ":v" + candidate.versionNo() + ":" + candidate.chunkId(),
                candidate.title(),
                candidate.versionNo(),
                snippet,
                "/api/v1/workspaces/" + workspacePublicId + "/knowledge/documents/"
                        + candidate.documentId() + "/versions/" + candidate.versionId() + "/source");
    }
}
