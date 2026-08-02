package com.insightflow.repository;

import com.insightflow.entity.FeedbackSource;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 反馈来源持久化端口。
 *
 * <p>查询按 Workspace 和来源类型共同过滤，防止把另一个 Workspace 的 CSV 来源复用到当前导入。</p>
 */
public interface FeedbackSourceRepository extends JpaRepository<FeedbackSource, Long> {

    /**
     * 读取某 Workspace 的唯一 CSV 来源；未命中时由服务层创建，不能全局按名称查询。
     */
    Optional<FeedbackSource> findByWorkspaceIdAndSourceType(Long workspaceId, String sourceType);
}
