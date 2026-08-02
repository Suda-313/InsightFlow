package com.insightflow.service.analysis;

/**
 * Topic Pack 目录中的一个 L1 议题定义；仅描述展示与告警资格，不携带分类规则本身
 * （规则见 {@link IssueRule}，由 {@link TopicPackLoader#rules()} 单独暴露）。
 *
 * @param canonicalKey  稳定议题键，如 topic_network；与 issue_catalog.canonical_key 对齐
 * @param name          用户可读议题名，用于 Dashboard 钻取展示
 * @param alertEligible 是否参与 EWMA 告警；topic_general 固定 false，避免笼统议题触发告警噪声
 * @param sortOrder     Dashboard 钻取时的展示顺序，数值越小越靠前
 */
public record TopicPackTopic(String canonicalKey, String name, boolean alertEligible, int sortOrder) {
}
