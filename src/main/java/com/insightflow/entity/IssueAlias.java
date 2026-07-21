package com.insightflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 规则或未来人工/模型建议的别名；本期 origin 固定 "rule"，不允许它自行改写统计结果。
 */
@Entity
@Table(name = "issue_alias")
public class IssueAlias {

    /**
     * 内部主键。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 一级租户隔离键；与唯一约束 (workspace_id, normalized_alias) 共同防重复。
     */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /**
     * 关联的 issue_catalog 内部主键。
     */
    @Column(name = "issue_id", nullable = false, updatable = false)
    private Long issueId;

    /**
     * 归一化后的别名文本。
     */
    @Column(name = "normalized_alias", nullable = false, length = 300, updatable = false)
    private String normalizedAlias;

    /**
     * 来源标识：rule / llm / manual。
     */
    @Column(nullable = false, length = 30, updatable = false)
    private String origin;

    /**
     * 记录首次写入时刻。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * JPA 反射构造器；业务代码使用 {@link #ruleAlias} 工厂方法。
     */
    protected IssueAlias() {
    }

    /**
     * 创建规则来源别名；唯一约束 (workspace_id, normalized_alias) 防重复。
     *
     * @param workspaceId     一级租户隔离键
     * @param issueId         关联主题目录内部主键
     * @param normalizedAlias 归一化别名文本
     * @return 新建的规则别名
     */
    public static IssueAlias ruleAlias(Long workspaceId, Long issueId, String normalizedAlias) {
        IssueAlias alias = new IssueAlias();
        OffsetDateTime now = OffsetDateTime.now();
        alias.workspaceId = workspaceId;
        alias.issueId = issueId;
        alias.normalizedAlias = normalizedAlias;
        alias.origin = "rule";
        alias.createdAt = now;
        return alias;
    }

    public Long getId() {
        return id;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public Long getIssueId() {
        return issueId;
    }

    public String getNormalizedAlias() {
        return normalizedAlias;
    }

    public String getOrigin() {
        return origin;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
