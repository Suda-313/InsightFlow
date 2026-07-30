package com.insightflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 金标题的必要事实或禁止断言。
 *
 * <p>开放式回答不存标准答案全文，而是分别评分 REQUIRED_FACT 与 FORBIDDEN_CLAIM。</p>
 */
@Entity
@Table(name = "rag_gold_case_assertion")
public class RagGoldCaseAssertion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    @Column(name = "case_id", nullable = false, updatable = false)
    private Long caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "assertion_type", nullable = false, length = 20, updatable = false)
    private RagGoldAssertionType assertionType;

    @Column(name = "assertion_text", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String assertionText;

    @Column(nullable = false, updatable = false)
    private double weight;

    @Column(name = "sort_order", nullable = false, updatable = false)
    private int sortOrder;

    protected RagGoldCaseAssertion() {
    }

    public static RagGoldCaseAssertion create(
            Long workspaceId,
            Long caseId,
            RagGoldAssertionType assertionType,
            String assertionText,
            double weight,
            int sortOrder) {
        if (workspaceId == null || caseId == null || assertionType == null) {
            throw new IllegalArgumentException("断言必须绑定 Workspace、题目与类型");
        }
        if (assertionText == null || assertionText.isBlank()) {
            throw new IllegalArgumentException("assertion_text 不能为空");
        }
        if (weight <= 0) {
            throw new IllegalArgumentException("weight 必须大于 0");
        }
        RagGoldCaseAssertion assertion = new RagGoldCaseAssertion();
        assertion.workspaceId = workspaceId;
        assertion.caseId = caseId;
        assertion.assertionType = assertionType;
        assertion.assertionText = assertionText.trim();
        assertion.weight = weight;
        assertion.sortOrder = sortOrder;
        return assertion;
    }

    public Long getId() { return id; }
    public Long getWorkspaceId() { return workspaceId; }
    public Long getCaseId() { return caseId; }
    public RagGoldAssertionType getAssertionType() { return assertionType; }
    public String getAssertionText() { return assertionText; }
    public double getWeight() { return weight; }
    public int getSortOrder() { return sortOrder; }
}
