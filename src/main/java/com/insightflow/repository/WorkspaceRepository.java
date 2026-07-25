package com.insightflow.repository;

import com.insightflow.entity.Workspace;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {

    Optional<Workspace> findByPublicId(UUID publicId);

    List<Workspace> findAllByOrderByCreatedAtDesc();

    /**
     * Owner 只可列出其所属组织下的 Workspace，查询必须以组织内部键限定范围。
     */
    List<Workspace> findByOrganizationIdInOrderByCreatedAtDesc(Collection<Long> organizationIds);

    /**
     * 非 Owner 只可列出明确授权的 Workspace，不能按名称或全局时间线扫描。
     */
    List<Workspace> findByIdInOrderByCreatedAtDesc(Collection<Long> workspaceIds);
}
