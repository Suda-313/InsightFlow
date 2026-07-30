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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 依据当前 Workspace 可见的已发布知识生成 RAG 金标题集。
 *
 * <p>用途为 {@link RagEvaluationFixturePurpose#TEST_FIXTURE} 与
 * {@link RagEvaluationFixturePurpose#CORPUS_HEALTH_CHECK}；生产质量门禁应使用
 * {@link RagGoldManualEvaluationRunner} 加载已发布人工金标，而非本动态 Fixture。</p>
 *
 * <p>它只使用组织、Workspace、文档类型与公开 UUID 建立预期，不读取 MinIO 原文。
 * 语料从「每种类型一篇」扩展到「每种类型可多篇」（design R1.3）后，题目改为按
 * {@code (类型, 文档新旧序号)} 定位：序号 0 是该类型最新发布的文档，1 是次新。
 * 这样同一类型内新增第二篇文档即可自然生成“跨文档混淆”题——问题仍然固定、
 * 不读取用户可编辑标题，但期望证据会指向不同文档，用来验证检索没有把同类型
 * 的旧文档或另一篇文档误当成正确来源。</p>
 */
@Component
public class RagEvaluationFixtureFactory {

    /** 本工厂生成的题集仅用于测试与语料健康检查，不作为生产质量门禁。 */
    public static final RagEvaluationFixturePurpose PURPOSE = RagEvaluationFixturePurpose.TEST_FIXTURE;

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
     * <p>某类型只发布了一篇文档时，只会生成该篇能覆盖到的题目，不会为了凑够题数虚构第二篇文档
     * 的问题；题数会随知识库语料从 &lt;10 篇扩展到 15 题以上目标而自然增长。
     * 无论语料多少，始终保留一题无依据问题，用来观测模型是否会在没有企业知识证据时编造结论。</p>
     */
    public RagEvaluationFixture create(UUID workspacePublicId) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        Map<KnowledgeDocumentType, List<FixtureDocument>> documentsByType = documentRepository
                .findByOrganizationIdOrderByCreatedAtDesc(workspace.getOrganizationId()).stream()
                .filter(document -> isVisibleToWorkspace(document, workspace.getId()))
                .map(this::asPublishedDocument)
                .flatMap(Optional::stream)
                // 仓储已按创建时间倒序返回，分组后每个类型内部仍保持“最新在前”，
                // 使模板的 documentIndex（0=最新）可以直接按下标取用，不需要二次排序。
                .collect(Collectors.groupingBy(FixtureDocument::type, LinkedHashMap::new, Collectors.toList()));

        List<RagEvaluationCaseDefinition> cases = new ArrayList<>();
        List<FixtureDocument> citedDocuments = new ArrayList<>();
        for (KnowledgeDocumentType type : KnowledgeDocumentType.values()) {
            List<FixtureDocument> documents = documentsByType.getOrDefault(type, List.of());
            List<GeneratedCase> generated = new ArrayList<>();
            for (QuestionTemplate template : templatesFor(type)) {
                if (template.documentIndex() >= documents.size()) {
                    continue;
                }
                FixtureDocument document = documents.get(template.documentIndex());
                generated.add(new GeneratedCase(template.question(), document));
            }
            appendCasesForType(type, generated, cases, citedDocuments);
        }
        cases.add(new RagEvaluationCaseDefinition(
                "no-knowledge", "no-knowledge",
                "请根据企业知识库说明虚构问题 RAG-EVAL-NO-KNOWLEDGE-9F3A 的处理办法。",
                Set.of()));
        return new RagEvaluationFixture(datasetVersion(citedDocuments), cases);
    }

    /**
     * 把一个类型内已经匹配到文档的模板落成正式题目。
     *
     * <p>只有一道题时沿用不带序号的稳定 case ID（兼容语料还未扩充的小 Workspace）；
     * 存在多道题时改为 {@code 类型-序号}，序号只反映本次生成顺序，不作为跨批次的持久主键。</p>
     */
    private void appendCasesForType(
            KnowledgeDocumentType type,
            List<GeneratedCase> generated,
            List<RagEvaluationCaseDefinition> cases,
            List<FixtureDocument> citedDocuments) {
        if (generated.isEmpty()) {
            return;
        }
        boolean needsSequenceSuffix = generated.size() > 1;
        int sequence = 0;
        for (GeneratedCase item : generated) {
            sequence++;
            String caseId = needsSequenceSuffix ? caseId(type) + "-" + sequence : caseId(type);
            cases.add(new RagEvaluationCaseDefinition(
                    caseId, category(type), item.question(),
                    Set.of("knowledge:" + item.document().documentPublicId() + ":")));
            if (!citedDocuments.contains(item.document())) {
                citedDocuments.add(item.document());
            }
        }
    }

    /** 当前组织通用文档或当前 Workspace 专属文档才可进入评测，与线上检索范围完全一致。 */
    private boolean isVisibleToWorkspace(KnowledgeDocument document, Long workspaceId) {
        return document.getTargetWorkspaceId() == null || document.getTargetWorkspaceId().equals(workspaceId);
    }

    /** 同一文档可有多个已发布版本；评测题绑定取 published_at 最新的一版，避免绑到旧版正文。 */
    private Optional<FixtureDocument> asPublishedDocument(KnowledgeDocument document) {
        return versionRepository.findByDocumentIdAndStatus(document.getId(), KnowledgeVersionStatus.PUBLISHED).stream()
                .max(java.util.Comparator.comparing(KnowledgeDocumentVersion::getPublishedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .map(version -> new FixtureDocument(document.getPublicId(), version.getPublicId(), document.getDocumentType()));
    }

    /** 类型到稳定 case ID 前缀的映射是评测契约，不能随页面文案变化。 */
    private String caseId(KnowledgeDocumentType type) {
        return switch (type) {
            case RELEASE_NOTE -> "release-note";
            case KNOWN_ISSUE -> "known-issue";
            case SUPPORT_SOP -> "support-sop";
            case SENTIMENT_PLAYBOOK -> "sentiment-playbook";
            case OPERATION_EVENT -> "operation-event";
            case POSTMORTEM -> "postmortem";
        };
    }

    /** 分类沿用不带序号的类型前缀，便于前端按类型归组展示同一类型下的多道题。 */
    private String category(KnowledgeDocumentType type) {
        return caseId(type);
    }

    /**
     * 每种类型固定的问题模板列表，按 {@code documentIndex}（0=该类型最新发布文档，1=次新）
     * 挑选期望证据来源。模板文本本身固定、不拼接用户可编辑的文档标题，避免提示注入面；
     * 同一文档的多条模板用于验证章节级检索（Phase R1 标题切分 + embed 前缀），
     * 指向不同 documentIndex 的模板用于验证跨文档混淆时是否召回到正确的那一篇。
     */
    private List<QuestionTemplate> templatesFor(KnowledgeDocumentType type) {
        return switch (type) {
            case RELEASE_NOTE -> List.of(
                    new QuestionTemplate("请说明当前已发布版本公告中的重要变更及适用限制。", 0),
                    new QuestionTemplate("请说明当前已发布版本公告中列出的已知问题关联和运营观察要求。", 0),
                    new QuestionTemplate("请说明上一个已发布版本公告中的主要调整内容。", 1));
            case KNOWN_ISSUE -> List.of(
                    new QuestionTemplate("请说明当前已发布已知问题中结算类问题的处理方式和升级条件。", 0),
                    new QuestionTemplate("请说明当前已发布已知问题中举报类反馈的处置边界。", 0),
                    new QuestionTemplate("请说明已归档的历史已知问题记录中崩溃类问题的处理结论。", 1));
            case SUPPORT_SOP -> List.of(
                    new QuestionTemplate("请说明当前已发布客服处理流程中的风险分级判断条件和时限。", 0),
                    new QuestionTemplate("请说明当前已发布客服处理流程中对外回复口径的边界要求。", 0),
                    new QuestionTemplate("请说明玩家常见问题中关于卡顿反馈应如何收集信息并提交。", 1),
                    new QuestionTemplate("请说明玩家常见问题中关于匹配和外挂举报类反馈的处理说明。", 1));
            case SENTIMENT_PLAYBOOK -> List.of(
                    new QuestionTemplate("请说明当前已发布舆情处置手册中判断高风险事件需要同时检查的维度。", 0),
                    new QuestionTemplate("请说明当前已发布舆情处置手册中的告警复核流程步骤。", 0),
                    new QuestionTemplate("请说明玩法机制参考中如何区分正常玩法难度与真实故障。", 1),
                    new QuestionTemplate("请说明玩法机制参考中的核心游戏循环环节。", 1));
            case OPERATION_EVENT, POSTMORTEM -> List.of();
        };
    }

    /** 仅对公开 UUID 和版本信息哈希，避免数据集版本反推出企业文档正文。 */
    private String datasetVersion(List<FixtureDocument> documents) {
        String material = documents.stream()
                .map(item -> item.type().name() + ":" + item.documentPublicId() + ":" + item.versionPublicId())
                .sorted()
                .collect(Collectors.joining("|"));
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

    /** 固定问题文本与其期望证据所在的文档新旧序号，不携带任何仓储或用户可编辑数据。 */
    private record QuestionTemplate(String question, int documentIndex) {
    }

    /** 模板与实际匹配到的文档配对后的中间结果，只在生成期使用，不对外暴露。 */
    private record GeneratedCase(String question, FixtureDocument document) {
    }
}
