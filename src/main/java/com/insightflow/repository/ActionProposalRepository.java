package com.insightflow.repository;

import com.insightflow.entity.ActionProposal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 提案读取始终以 Workspace 内部键为第一隔离条件。 */
public interface ActionProposalRepository extends JpaRepository<ActionProposal, Long> {
    /** 单提案读取必须同时验证当前 Workspace。 */
    Optional<ActionProposal> findByWorkspaceIdAndPublicId(Long workspaceId, UUID publicId);
    /** 调查中心展示当前调查的所有提案。 */
    List<ActionProposal> findByWorkspaceIdAndInvestigationCaseIdOrderByCreatedAtAsc(Long workspaceId, Long investigationCaseId);
}
