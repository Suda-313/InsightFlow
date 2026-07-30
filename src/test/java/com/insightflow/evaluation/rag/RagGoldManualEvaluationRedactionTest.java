package com.insightflow.evaluation.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * FROZEN split 脱敏规则：逐题结果不得包含断言、模型文本或 chunk 内容字段。
 */
class RagGoldManualEvaluationRedactionTest {

    @Test
    void frozenRedactedCaseResultOnlyExposesSafeFields() {
        RagGoldManualEvaluationCaseResult result = RagGoldManualEvaluationCaseResult.frozenRedacted(
                "case-key-1", "failed", "retrieval_timeout", "retrieval_timeout");

        assertThat(result.caseKey()).isEqualTo("case-key-1");
        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.failureStage()).isEqualTo("retrieval_timeout");
        assertThat(result.errorCode()).isEqualTo("retrieval_timeout");
        assertThat(result.questionType()).isNull();
        assertThat(result.expectedEvidenceCount()).isNull();
        assertThat(result.citedEvidenceCount()).isNull();
        assertThat(result.requiredFactCoverageRate()).isNull();
        assertThat(result.forbiddenClaimHitRate()).isNull();
        assertThat(result.ungrounded()).isNull();
    }
}
