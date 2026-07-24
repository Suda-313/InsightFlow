package com.insightflow.knowledge;

import com.insightflow.entity.KnowledgeDocumentType;
import com.insightflow.entity.Workspace;
import com.insightflow.service.WorkspaceService;
import java.util.List;
import java.util.UUID;
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

    /** 所有依赖均由 Spring 注入，便于单测替换模型和数据库边界。 */
    public KnowledgeSearchTool(WorkspaceService workspaces, KnowledgeEmbeddingGateway embeddings,
            KnowledgeVectorStore vectors, KnowledgeRetrievalPlanner planner,
            KnowledgeEvidenceGuardrail evidenceGuardrail) {
        this.workspaces = workspaces;
        this.embeddings = embeddings;
        this.vectors = vectors;
        this.planner = planner;
        this.evidenceGuardrail = evidenceGuardrail;
    }

    /**
     * 首轮按问题类型检索；只有首轮已被收窄且证据不足时，才放宽类型补检索一次。
     * 即使补检索无结果也立即返回，绝不由模型决定继续调用次数。
     */
    public KnowledgeRetrievalResult retrieve(UUID workspacePublicId, String question) {
        Workspace workspace = workspaces.get(workspacePublicId);
        List<Double> queryEmbedding = embeddings.embed(List.of(question)).get(0);
        List<KnowledgeDocumentType> firstRoundTypes = planner.plan(question);
        List<KnowledgeVectorStore.SearchCandidate> candidates = vectors.search(
                workspace.getOrganizationId(), workspace.getId(), question, firstRoundTypes,
                queryEmbedding, MAX_EVIDENCE_COUNT);

        int rounds = 1;
        if (!firstRoundTypes.isEmpty() && !evidenceGuardrail.isSufficient(candidates)) {
            candidates = vectors.search(workspace.getOrganizationId(), workspace.getId(), question,
                    List.of(), queryEmbedding, MAX_EVIDENCE_COUNT);
            rounds = 2;
        }

        List<KnowledgeEvidence> evidence = candidates.stream()
                .map(candidate -> new KnowledgeEvidence(
                        "knowledge:" + candidate.documentId() + ":v" + candidate.versionNo() + ":" + candidate.chunkId(),
                        candidate.title(), candidate.versionNo(),
                        candidate.content().substring(0, Math.min(300, candidate.content().length())),
                        "/api/v1/workspaces/" + workspacePublicId + "/knowledge/documents/"
                                + candidate.documentId() + "/versions/" + candidate.versionId() + "/source"))
                .toList();
        return new KnowledgeRetrievalResult(rounds, evidence);
    }
}
