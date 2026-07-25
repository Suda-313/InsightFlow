package com.insightflow.investigation;

import java.util.UUID;

/**
 * 告警持久化成功后发布的进程内领域事件。
 *
 * <p>事件只携带内部隔离键和公开告警 UUID，不携带可变 Alert 实体或原始证据内容；监听器在原事务提交后才创建调查任务，避免回滚时留下孤立待办。</p>
 */
public record AlertCreatedEvent(Long workspaceId, Long alertId, UUID alertPublicId) {
}
