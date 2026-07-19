package com.insightflow.repository;

import com.insightflow.entity.Workspace;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Workspace 的持久化端口实现。
 *
 * <p>继承 Spring Data 的标准 CRUD 能力；领域代码按内部 {@link Long} 关联，API 查询按
 * {@code publicId} 定位，避免将内部主键泄漏到边界。</p>
 */
public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {

    /**
     * 按对外 UUIDv7 查找单个工作区；未命中时由应用服务转换成统一的 404 语义。
     */
    Optional<Workspace> findByPublicId(UUID publicId);
}
