package com.insightflow.entity;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 人工金标数据集中的一条评测题。
 *
 * <p>对外使用 {@code publicId} 与稳定 {@code caseKey}；Runner 冻结运行记录时应持久化 caseKey 列表，
 * 而不是内部自增 id。</p>
 */
@Entity
@Table(name = "rag_gold_case")
public class RagGoldCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    @Column(name = "dataset_id", nullable = false, updatable = false)
    private Long datasetId;

    @Column(name = "case_key", nullable = false, length = 120, updatable = false)
    private String caseKey;

    @Column(name = "question_text", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String questionText;

    @Enumerated(EnumType.STRING)
    @Column(name = "question_type", nullable = false, length = 40, updatable = false)
    private RagGoldQuestionType questionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private RagGoldDifficulty difficulty;

    @Column(name = "should_refuse", nullable = false, updatable = false)
    private boolean shouldRefuse;

    @Column(name = "annotation_basis", length = 500, updatable = false)
    private String annotationBasis;

    @Column(length = 120, updatable = false)
    private String reviewer;

    @Column(name = "sort_order", nullable = false, updatable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected RagGoldCase() {
    }

    public static RagGoldCase create(
            Long workspaceId,
            Long datasetId,
            String caseKey,
            String questionText,
            RagGoldQuestionType questionType,
            RagGoldDifficulty difficulty,
            boolean shouldRefuse,
            String annotationBasis,
            String reviewer,
            int sortOrder) {
        if (workspaceId == null || datasetId == null) {
            throw new IllegalArgumentException("题目必须绑定 Workspace 与数据集");
        }
        if (caseKey == null || caseKey.isBlank()) {
            throw new IllegalArgumentException("case_key 不能为空");
        }
        if (questionText == null || questionText.isBlank()) {
            throw new IllegalArgumentException("question_text 不能为空");
        }
        if (questionType == null || difficulty == null) {
            throw new IllegalArgumentException("question_type 与 difficulty 不能为空");
        }
        RagGoldCase goldCase = new RagGoldCase();
        goldCase.publicId = UuidCreator.getTimeOrdered();
        goldCase.workspaceId = workspaceId;
        goldCase.datasetId = datasetId;
        goldCase.caseKey = caseKey.trim();
        goldCase.questionText = questionText.trim();
        goldCase.questionType = questionType;
        goldCase.difficulty = difficulty;
        goldCase.shouldRefuse = shouldRefuse;
        goldCase.annotationBasis = annotationBasis == null ? null : annotationBasis.trim();
        goldCase.reviewer = reviewer == null ? null : reviewer.trim();
        goldCase.sortOrder = sortOrder;
        goldCase.createdAt = OffsetDateTime.now();
        return goldCase;
    }

    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public Long getWorkspaceId() { return workspaceId; }
    public Long getDatasetId() { return datasetId; }
    public String getCaseKey() { return caseKey; }
    public String getQuestionText() { return questionText; }
    public RagGoldQuestionType getQuestionType() { return questionType; }
    public RagGoldDifficulty getDifficulty() { return difficulty; }
    public boolean isShouldRefuse() { return shouldRefuse; }
    public String getAnnotationBasis() { return annotationBasis; }
    public String getReviewer() { return reviewer; }
    public int getSortOrder() { return sortOrder; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
