package com.insightflow.repository;

import com.insightflow.entity.IssueCatalog;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 主题目录持久化端口；按 workspace + canonical_key 查找以实现 find-or-create。 */
public interface IssueCatalogRepository extends JpaRepository<IssueCatalog, Long> {
    Optional<IssueCatalog> findByWorkspaceIdAndCanonicalKey(Long workspaceId, String canonicalKey);
}
