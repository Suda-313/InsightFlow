package com.insightflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;

/**
 * 一个 Cell 内某主题的计数与有限样本引用；sample_event_ids 只存内部 id，不存文本。
 */
@Entity
@Table(name = "cell_issue")
public class CellIssue {

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
     * 所属 Data Cell 内部主键。
     */
    @Column(name = "data_cell_id", nullable = false, updatable = false)
    private Long dataCellId;

    /**
     * 关联的主题目录内部主键。
     */
    @Column(name = "issue_id", nullable = false, updatable = false)
    private Long issueId;

    /**
     * 该主题在本 Cell 内的出现次数。
     */
    @Column(name = "mention_count", nullable = false, updatable = false)
    private int mentionCount;

    /**
     * 用于证据回溯的有限反馈事件 id 集合，以 JSON 数组字符串保存。
     */
    @Column(name = "sample_event_ids", nullable = false, columnDefinition = "jsonb", updatable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String sampleEventIdsJson;

    /**
     * 记录首次写入时刻。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * JPA 反射构造器；业务代码使用 {@link #of} 工厂方法。
     */
    protected CellIssue() {
    }

    /**
     * 创建一条 Cell 内主题计数；sampleEventIdsJson 为 JSON 数组字符串。
     *
     * @param workspaceId        一级租户隔离键
     * @param dataCellId         所属 Data Cell 内部主键
     * @param issueId            主题目录内部主键
     * @param mentionCount       主题出现次数
     * @param sampleEventIdsJson 样本事件 id JSON 数组字符串
     * @return 新建的 Cell-主题计数
     */
    public static CellIssue of(
            Long workspaceId, Long dataCellId, Long issueId,
            int mentionCount, String sampleEventIdsJson) {
        CellIssue cellIssue = new CellIssue();
        OffsetDateTime now = OffsetDateTime.now();
        cellIssue.workspaceId = workspaceId;
        cellIssue.dataCellId = dataCellId;
        cellIssue.issueId = issueId;
        cellIssue.mentionCount = mentionCount;
        cellIssue.sampleEventIdsJson = sampleEventIdsJson;
        cellIssue.createdAt = now;
        return cellIssue;
    }

    public Long getId() {
        return id;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public Long getDataCellId() {
        return dataCellId;
    }

    public Long getIssueId() {
        return issueId;
    }

    public int getMentionCount() {
        return mentionCount;
    }

    public String getSampleEventIdsJson() {
        return sampleEventIdsJson;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
