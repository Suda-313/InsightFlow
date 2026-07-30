package com.insightflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** P3 覆盖感知 Top8：双文档保留与同文档冗余惩罚。 */
class KnowledgeCoverageAwareSelectorTest {

    private KnowledgeCoverageAwareSelector selector;

    @BeforeEach
    void setUp() {
        KnowledgeCrossQueryDecomposer decomposer = new KnowledgeCrossQueryDecomposer();
        selector = new KnowledgeCoverageAwareSelector(new KnowledgeTitleEntityScoreBooster(decomposer));
    }

    @Test
    void keepsBothEntityDocumentsInTopEightForCrossQuery() {
        UUID signinDoc = UUID.randomUUID();
        UUID gushuDoc = UUID.randomUUID();
        List<KnowledgeVectorStore.SearchCandidate> pool = List.of(
                candidate(signinDoc, "超自然行动组暑期签到活动运营档案", 0.20, 1),
                candidate(signinDoc, "超自然行动组暑期签到活动运营档案", 0.19, 2),
                candidate(signinDoc, "超自然行动组暑期签到活动运营档案", 0.18, 3),
                candidate(signinDoc, "超自然行动组暑期签到活动运营档案", 0.17, 4),
                candidate(signinDoc, "超自然行动组暑期签到活动运营档案", 0.16, 5),
                candidate(signinDoc, "超自然行动组暑期签到活动运营档案", 0.15, 6),
                candidate(signinDoc, "超自然行动组暑期签到活动运营档案", 0.14, 7),
                candidate(signinDoc, "超自然行动组暑期签到活动运营档案", 0.13, 8),
                candidate(gushuDoc, "超自然行动组古蜀遗迹联动活动公告", 0.05, 9));

        List<KnowledgeVectorStore.SearchCandidate> selected = selector.select(
                "复盘会上需要确认：暑期签到和古蜀活动的时间窗有没有重叠？",
                pool,
                8,
                KnowledgeRetrievalOptions.withDecomposition(
                        false,
                        List.of(
                                "复盘会上需要确认：暑期签到的时间窗",
                                "复盘会上需要确认：古蜀遗迹联动活动公告 古蜀活动的时间窗"),
                        "CROSS_DOCUMENT"));

        assertThat(selected.stream().map(KnowledgeVectorStore.SearchCandidate::documentId).distinct())
                .contains(signinDoc, gushuDoc);
    }

    @Test
    void singleQuestionUsesScoreOrderTruncationWithoutGreedyReorder() {
        UUID highDoc = UUID.randomUUID();
        UUID lowDoc = UUID.randomUUID();
        List<KnowledgeVectorStore.SearchCandidate> pool = List.of(
                candidate(highDoc, "超自然行动组-1.4-版本更新说明", 0.30, 1),
                candidate(highDoc, "超自然行动组-1.4-版本更新说明", 0.29, 2),
                candidate(highDoc, "超自然行动组-1.4-版本更新说明", 0.28, 3),
                candidate(lowDoc, "超自然行动组玩家常见问题FAQ", 0.05, 4));

        List<KnowledgeVectorStore.SearchCandidate> selected = selector.select(
                "1.4 公告里奖励到账 SLA 是多少？",
                pool,
                2,
                KnowledgeRetrievalOptions.withDecomposition(false, null, "SINGLE_DOCUMENT_FACT"));

        assertThat(selected).hasSize(2);
        assertThat(selected.get(0).documentId()).isEqualTo(highDoc);
        assertThat(selected.get(1).documentId()).isEqualTo(highDoc);
    }

    @Test
    void crossQuestionStillUsesCoverageSelection() {
        assertThat(KnowledgeCoverageAwareSelector.usesCoverageSelection(
                new KnowledgeTitleEntityScoreBooster.QuerySignals(List.of(), List.of(), List.of(), Set.of()),
                KnowledgeRetrievalOptions.withDecomposition(false, null, "CROSS_DOCUMENT")))
                .isTrue();
        assertThat(KnowledgeCoverageAwareSelector.usesCoverageSelection(
                new KnowledgeTitleEntityScoreBooster.QuerySignals(List.of(), List.of(), List.of(), Set.of()),
                KnowledgeRetrievalOptions.withDecomposition(false, null, "SINGLE_DOCUMENT_FACT")))
                .isFalse();
    }

    private static KnowledgeVectorStore.SearchCandidate candidate(
            UUID documentId, String title, double score, int chunkNo) {
        return new KnowledgeVectorStore.SearchCandidate(
                documentId,
                UUID.randomUUID(),
                3,
                UUID.randomUUID(),
                title,
                "content-" + chunkNo,
                score,
                "RELEASE_NOTE",
                "section-" + chunkNo,
                null);
    }
}
