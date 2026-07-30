package com.insightflow.evaluation.rag;

/**
 * 单题单个 requirement_key 组的检索漏斗诊断。
 *
 * <p>用于区分 CROSS/VERSION 题「部分组命中」与「全组覆盖」；rank 基于组内 OR 语义的首个 relevant chunk。</p>
 */
public record RagGoldRequirementGroupDiagnostics(
        /** 金标 requirement_key；无 key 时为 {@code __solo_N} 占位。 */
        String groupKey,
        /** 该组首个 relevant chunk 在 RRF 候选 Top50 中的 rank（1-based），未命中为 0。 */
        int rrfFirstRank,
        /** 该组首个 relevant chunk 在最终 Top8 中的 rank（1-based），未命中为 0。 */
        int finalFirstRank,
        /** 该组是否在最终 Top8 内被满足（组内 OR）。 */
        boolean satisfiedAt8) {
}
