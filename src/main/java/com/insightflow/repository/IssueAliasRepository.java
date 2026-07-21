package com.insightflow.repository;

import com.insightflow.entity.IssueAlias;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 别名持久化端口；按 workspace + normalized_alias 判重，防规则重复写别名。 */
public interface IssueAliasRepository extends JpaRepository<IssueAlias, Long> {
    Optional<IssueAlias> findByWorkspaceIdAndNormalizedAlias(Long workspaceId, String normalizedAlias);
    boolean existsByWorkspaceIdAndNormalizedAlias(Long workspaceId, String normalizedAlias);
}
