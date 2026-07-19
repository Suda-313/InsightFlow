package com.insightflow.importing.infrastructure;

import com.insightflow.importing.domain.FeedbackEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 脱敏反馈事实的持久化端口。
 *
 * <p>行级幂等由 Workspace、来源和外部引用哈希共同决定；内容哈希仅用于后续近似重复分析。</p>
 */
public interface FeedbackEventRepository extends JpaRepository<FeedbackEvent, Long> {

    /**
     * 在写入前查询完全相同的外部记录，避免重复导入造成重复反馈和虚假告警。
     */
    boolean existsByWorkspaceIdAndSourceIdAndExternalRefHash(
            Long workspaceId, Long sourceId, String externalRefHash);
}
