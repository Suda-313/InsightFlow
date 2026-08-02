package com.insightflow.service.analysis;

import java.time.OffsetDateTime;

/**
 * 投影输入事件的计算视图；只暴露切分与分类所需字段，不携带脱敏原文之外的持久化字段.
 *
 * <p>EventInput 是 DataCellBuilder 和 IssueClassifier 之间的公共契约，
 * 保证切分阶段只依赖 id、发生时间、来源种类和归一化文本，避免引入存储层细节。</p>
 *
 * @param id              feedback_event 内部主键，用于 cell_issue.sample_event_ids
 * @param occurredAt      反馈真实发生时间，决定时间窗与排序
 * @param sourceKind      事件来源种类（工单/评价/…），用于按来源分类聚合
 * @param normalizedText  归一后文本，用于 token 估算与分类
 */
public record EventInput(Long id, OffsetDateTime occurredAt, String sourceKind, String normalizedText) {
}
