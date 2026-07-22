package com.insightflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 知识库条目；RAG 检索的最小单元，当前阶段使用 LIKE 模糊搜索，
 * 后续切换为 pgvector 向量搜索。
 */
@Entity
@Table(name = "knowledge_entry")
public class KnowledgeEntry {

    /**
     * 内部主键。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 一级租户隔离键。
     */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /**
     * 条目标题。
     */
    @Column(nullable = false, length = 200)
    private String title;

    /**
     * 条目正文。
     */
    @Column(nullable = false)
    private String content;

    /**
     * 分类标签。
     */
    @Column(nullable = false, length = 50)
    private String category;

    /**
     * 来源：manual / import / webhook。
     */
    @Column(nullable = false, length = 30)
    private String source;

    /**
     * 向量嵌入（预留，当前阶段未使用）。
     */
    @Column(length = 10000)
    private String embedding;

    /**
     * 扩展元数据 JSON 字符串。
     */
    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    /**
     * 记录首次写入时刻。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * 记录最后更新时刻。
     */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * JPA 反射构造器；业务代码使用 {@link #create} 工厂方法。
     */
    protected KnowledgeEntry() {
    }

    /**
     * 创建一个知识库条目。
     *
     * @param workspaceId 一级租户隔离键
     * @param title       条目标题
     * @param content     条目正文
     * @param category    分类标签
     * @param source      来源
     * @param metadataJson 扩展元数据
     * @return 新建的知识库条目
     */
    public static KnowledgeEntry create(
            Long workspaceId, String title, String content,
            String category, String source, String metadataJson) {
        KnowledgeEntry entry = new KnowledgeEntry();
        OffsetDateTime now = OffsetDateTime.now();
        entry.workspaceId = workspaceId;
        entry.title = title;
        entry.content = content;
        entry.category = category;
        entry.source = source;
        entry.metadataJson = metadataJson;
        entry.createdAt = now;
        entry.updatedAt = now;
        return entry;
    }

    public Long getId() {
        return id;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public String getCategory() {
        return category;
    }

    public String getSource() {
        return source;
    }

    public String getEmbedding() {
        return embedding;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}