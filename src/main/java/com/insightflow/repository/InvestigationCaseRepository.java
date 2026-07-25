package com.insightflow.repository;

import com.insightflow.entity.InvestigationCase;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 调查卡片仓储只提供带 Workspace 隔离条件的外部 UUID 查询。
 */
public interface InvestigationCaseRepository extends JpaRepository<InvestigationCase, Long> {

    /** 同一告警只能映射一张调查卡片，是 API 幂等读取的基础。 */
    Optional<InvestigationCase> findByWorkspaceIdAndAlertId(Long workspaceId, Long alertId);

    /** 任务 Worker 必须同时验证任务和工作区归属，防止错误任务写入别的卡片。 */
    Optional<InvestigationCase> findByAsyncTaskIdAndWorkspaceId(Long asyncTaskId, Long workspaceId);

    /** 调查中心列表只返回当前 Workspace 的卡片。 */
    List<InvestigationCase> findByWorkspaceIdOrderByUpdatedAtDesc(Long workspaceId);

    /** 单条卡片读取使用公开 UUID 与 Workspace 双重过滤。 */
    Optional<InvestigationCase> findByWorkspaceIdAndPublicId(Long workspaceId, UUID publicId);

    /** 已确认卡片是报告证据的唯一有效调查来源。 */
    List<InvestigationCase> findByWorkspaceIdAndStatusOrderByUpdatedAtDesc(Long workspaceId, String status);
}
