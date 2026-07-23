package com.insightflow.repository;

import com.insightflow.entity.Workspace;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {

    Optional<Workspace> findByPublicId(UUID publicId);

    List<Workspace> findAllByOrderByCreatedAtDesc();
}
