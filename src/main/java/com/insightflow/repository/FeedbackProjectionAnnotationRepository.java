package com.insightflow.repository;

import com.insightflow.entity.FeedbackProjectionAnnotation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * L2 标注持久化端口；唯一约束 (workspace_projection_id, feedback_event_id) 防重试重复累计。
 */
public interface FeedbackProjectionAnnotationRepository extends JpaRepository<FeedbackProjectionAnnotation, Long> {

    /**
     * 按 Workspace + L2 主标签查询标注行，供 Dashboard L2→L1 钻取定位对应的反馈事件与投影范围。
     *
     * @param workspaceId       一级租户隔离键
     * @param primaryExpression L2 稳定键，如 expr_suggestion
     * @return 命中该 L2 类目的全部标注行
     */
    List<FeedbackProjectionAnnotation> findByWorkspaceIdAndPrimaryExpression(Long workspaceId, String primaryExpression);

    /** 首屏 L2 分布计数与钻取 API 同源，按 Workspace 汇总全部标注行。 */
    List<FeedbackProjectionAnnotation> findByWorkspaceId(Long workspaceId);

    /** 幂等守卫：判断某次投影是否已写入 L2 标注（与 data_cell 成对出现才算完成）。 */
    long countByWorkspaceProjectionIdAndWorkspaceId(Long workspaceProjectionId, Long workspaceId);

    /** 工作区级 L2 行数，供半完成投影启动恢复判定。 */
    long countByWorkspaceId(Long workspaceId);
}
