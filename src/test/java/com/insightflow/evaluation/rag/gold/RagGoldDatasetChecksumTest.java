package com.insightflow.evaluation.rag.gold;

import static org.assertj.core.api.Assertions.assertThat;

import com.insightflow.entity.RagGoldCase;
import com.insightflow.entity.RagGoldCaseAssertion;
import com.insightflow.entity.RagGoldCaseEvidence;
import com.insightflow.entity.RagGoldDataset;
import com.insightflow.entity.RagGoldDatasetSplit;
import com.insightflow.entity.RagGoldAssertionType;
import com.insightflow.entity.RagGoldDifficulty;
import com.insightflow.entity.RagGoldEvidenceGranularity;
import com.insightflow.entity.RagGoldQuestionType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** checksum 对同样内容应稳定，对内容变化应敏感。 */
class RagGoldDatasetChecksumTest {

    private static final UUID DOC = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID VER = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID CHUNK = UUID.fromString("00000000-0000-4000-8000-000000000003");

    @Test
    void producesStableChecksumForSameContent() {
        RagGoldDataset dataset = RagGoldDataset.createDraft(
                7L, 3L, "ops-rag", "v1", RagGoldDatasetSplit.DEVELOPMENT, "corpus:v1");
        RagGoldCase goldCase = RagGoldCase.create(
                7L, 1L, "case-1", "问题", RagGoldQuestionType.SINGLE_DOCUMENT_FACT,
                RagGoldDifficulty.EASY, false, "标注依据", "reviewer", 0);
        RagGoldCaseEvidence evidence = RagGoldCaseEvidence.create(
                7L, 1L, RagGoldEvidenceGranularity.CHUNK, DOC, VER, CHUNK, 0);
        RagGoldCaseAssertion assertion = RagGoldCaseAssertion.create(
                7L, 1L, RagGoldAssertionType.REQUIRED_FACT, "关键事实", 1.0, 0);

        String first = RagGoldDatasetChecksum.compute(
                dataset, List.of(goldCase), List.of(evidence), List.of(assertion));
        String second = RagGoldDatasetChecksum.compute(
                dataset, List.of(goldCase), List.of(evidence), List.of(assertion));

        assertThat(first).isEqualTo(second).hasSize(64);
    }

    @Test
    void changesWhenQuestionTextChanges() {
        RagGoldDataset dataset = RagGoldDataset.createDraft(
                7L, 3L, "ops-rag", "v1", RagGoldDatasetSplit.DEVELOPMENT, "corpus:v1");
        RagGoldCase caseA = RagGoldCase.create(
                7L, 1L, "case-1", "问题A", RagGoldQuestionType.SINGLE_DOCUMENT_FACT,
                RagGoldDifficulty.EASY, false, null, null, 0);
        RagGoldCase caseB = RagGoldCase.create(
                7L, 1L, "case-1", "问题B", RagGoldQuestionType.SINGLE_DOCUMENT_FACT,
                RagGoldDifficulty.EASY, false, null, null, 0);

        String checksumA = RagGoldDatasetChecksum.compute(dataset, List.of(caseA), List.of(), List.of());
        String checksumB = RagGoldDatasetChecksum.compute(dataset, List.of(caseB), List.of(), List.of());

        assertThat(checksumA).isNotEqualTo(checksumB);
    }
}
