package com.insightflow.repository;

import com.insightflow.entity.ManualCorrection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 人工纠错候选按 Workspace 隔离读取。 */
public interface ManualCorrectionRepository extends JpaRepository<ManualCorrection, Long> {
    /** 单条候选读取必须同时验证工作区范围。 */ Optional<ManualCorrection> findByWorkspaceIdAndPublicId(Long workspaceId, UUID publicId);
    /** 调查中心按时间展示当前工作区纠错候选。 */ List<ManualCorrection> findByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId);
}
