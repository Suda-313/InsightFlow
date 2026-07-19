package com.insightflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 用户报告冻结的一份已投影文件关联。
 *
 * <p>它与 ProjectionFile 分离：同一导入文件可被任意多个报告引用，但只能进入一次成功的增量投影。</p>
 */
@Entity
@Table(name = "analysis_report_file")
public class AnalysisReportFile {

    /** 内部关联主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属报告内部键。 */
    @Column(name = "analysis_report_id", nullable = false, updatable = false)
    private Long analysisReportId;

    /** 强制租户隔离键。 */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 已完成投影的来源文件内部键。 */
    @Column(name = "import_file_id", nullable = false, updatable = false)
    private Long importFileId;

    /** 范围冻结时刻。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** JPA 所需构造器。 */
    protected AnalysisReportFile() {
    }

    /** 创建已完成 Workspace 归属校验的报告来源关联。 */
    public static AnalysisReportFile of(Long reportId, Long workspaceId, Long importFileId) {
        AnalysisReportFile link = new AnalysisReportFile();
        link.analysisReportId = reportId;
        link.workspaceId = workspaceId;
        link.importFileId = importFileId;
        link.createdAt = OffsetDateTime.now();
        return link;
    }
}
