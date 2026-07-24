package com.insightflow.evaluation.rag;

import com.insightflow.entity.KnowledgeDocument;
import com.insightflow.entity.KnowledgeDocumentType;
import com.insightflow.entity.KnowledgeDocumentVersion;
import com.insightflow.entity.KnowledgeVersionStatus;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.KnowledgeDocumentRepository;
import com.insightflow.repository.KnowledgeDocumentVersionRepository;
import com.insightflow.service.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 依据当前 Workspace 可见的已发布知识生成 RAG 金标题集。
 *
 * <p>它只使用组织、Workspace、文档类型与公开 UUID 建立预期，不读取 MinIO 原文，
 * 并且每种文档类型最多选择一篇，控制一次评测的模型调用成本和上下文范围。</p>
 */
@Component
public class RagEvaluationFixtureFactory {

    /** 解析公开 Workspace UUID，作为组织和可见范围的唯一可信入口。 */
    private final WorkspaceService workspaceService;

    /** 文档仓储仅按组织收敛候选，Workspace 专属过滤仍在本类显式执行。 */
    private final KnowledgeDocumentRepository documentRepository;

    /** 版本仓储只选择已发布版本，禁止待审核、失效或删除版本形成评测金标。 */
    private final KnowledgeDocumentVersionRepository versionRepository;

    /** 明确依赖以便在单测中验证组织与 Workspace 的边界。 */
    public RagEvaluationFixtureFactory(
            WorkspaceService workspaceService,
            KnowledgeDocumentRepository documentRepository,
            KnowledgeDocumentVersionRepository versionRepository) {
        this.workspaceService = workspaceService;
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
    }

    /**
     * 生成当前 Workspace 可重跑的题集。
     *
     * <p>发布文档不足四种类型时不会伪造题目；始终保留一题无依据问题，用来观测模型是否会在
     * 没有企业知识证据时编造结论。</p>
     */
    public RagEvaluationFixture create(UUID workspacePublicId) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        List<FixtureDocument> selected = documentRepository
                .findByOrganizationIdOrderByCreatedAtDesc(workspace.getOrganizationId()).stream()
                .filter(document -> isVisibleToWorkspace(document, workspace.getId()))
                .map(this::asPublishedDocument)
                .flatMap(java.util.Optional::stream)
                .sorted(Comparator.comparing(item -> item.type().name()))
                .collect(java.util.stream.Collectors.toMap(
                        FixtureDocument::type,
                        item -> item,
                        (first, ignored) -> first,
                        java.util.LinkedHashMap::new))
                .values().stream().toList();

        List<RagEvaluationCaseDefinition> cases = new ArrayList<>();
        selected.forEach(document -> cases.add(caseFor(document)));
        cases.add(new RagEvaluationCaseDefinition(
                "no-knowledge", "no-knowledge",
                "请根据企业知识库说明虚构问题 RAG-EVAL-NO-KNOWLEDGE-9F3A 的处理办法。",
                java.util.Set.of()));
        return new RagEvaluationFixture(datasetVersion(selected), cases);
    }

    /** 当前组织通用文档或当前 Workspace 专属文档才可进入评测，与线上检索范围完全一致。 */
    private boolean isVisibleToWorkspace(KnowledgeDocument document, Long workspaceId) {
        return document.getTargetWorkspaceId() == null || document.getTargetWorkspaceId().equals(workspaceId);
    }

    /** 同一文档的已发布版本由数据库唯一约束保证至多一个；异常数据不应扩大为多道重复题。 */
    private java.util.Optional<FixtureDocument> asPublishedDocument(KnowledgeDocument document) {
        return versionRepository.findByDocumentIdAndStatus(document.getId(), KnowledgeVersionStatus.PUBLISHED).stream()
                .findFirst()
                .map(version -> new FixtureDocument(document.getPublicId(), version.getPublicId(), document.getDocumentType()));
    }

    /** 题目文本按业务类型固定，避免将用户可编辑的文档标题带入模型输入造成提示注入面。 */
    private RagEvaluationCaseDefinition caseFor(FixtureDocument document) {
        return new RagEvaluationCaseDefinition(
                caseId(document.type()), category(document.type()), question(document.type()),
                java.util.Set.of("knowledge:" + document.documentPublicId() + ":"));
    }

    /** 类型到稳定 case ID 的映射是评测契约，不能随页面文案变化。 */
    private String caseId(KnowledgeDocumentType type) {
        return switch (type) {
            case RELEASE_NOTE -> "release-note";
            case KNOWN_ISSUE -> "known-issue";
            case SUPPORT_SOP -> "support-sop";
            case SENTIMENT_PLAYBOOK -> "sentiment-playbook";
        };
    }

    /** 分类沿用 case ID，便于前端展示又不引入额外的可变配置。 */
    private String category(KnowledgeDocumentType type) {
        return caseId(type);
    }

    /** 每种类型只问可由该类型资料回答的固定业务问题。 */
    private String question(KnowledgeDocumentType type) {
        return switch (type) {
            case RELEASE_NOTE -> "请说明当前已发布版本公告中的重要变更及适用限制。";
            case KNOWN_ISSUE -> "请说明当前已发布已知问题的影响范围和处理边界。";
            case SUPPORT_SOP -> "请说明当前已发布客服处理流程及需要升级处理的条件。";
            case SENTIMENT_PLAYBOOK -> "请说明当前已发布舆情处置原则及对外回应边界。";
        };
    }

    /** 仅对公开 UUID 和版本信息哈希，避免数据集版本反推出企业文档正文。 */
    private String datasetVersion(List<FixtureDocument> documents) {
        String material = documents.stream()
                .map(item -> item.type().name() + ":" + item.documentPublicId() + ":" + item.versionPublicId())
                .sorted()
                .collect(java.util.stream.Collectors.joining("|"));
        try {
            return "rag-gold:v1:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法，无法生成 RAG 数据集版本", exception);
        }
    }

    /** 内部候选只在本类使用，避免将内部版本关系键泄露到评测 API。 */
    private record FixtureDocument(UUID documentPublicId, UUID versionPublicId, KnowledgeDocumentType type) {
    }
}
