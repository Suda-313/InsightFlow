package com.insightflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
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

    /** Phase 4C：gold 在合并池第 8 位时，enforceSoftEntityCoverage 应从 Top30 快照 swap 进 Top8。 */
    @Test
    void promotesDeepPoolGroupMatchIntoTopEight() {
        UUID signinDoc = UUID.randomUUID();
        UUID faqDoc = UUID.randomUUID();
        UUID faqGold = UUID.randomUUID();
        List<KnowledgeVectorStore.SearchCandidate> pool = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            pool.add(candidate(signinDoc, "超自然行动组暑期签到活动运营档案", 0.30 - i * 0.01, i + 1));
        }
        pool.add(candidate(faqDoc, "超自然行动组玩家常见问题FAQ", 0.18, 8, faqGold));
        for (int i = 9; i <= 12; i++) {
            pool.add(candidate(signinDoc, "超自然行动组暑期签到活动运营档案", 0.17 - i * 0.005, i));
        }

        List<KnowledgeVectorStore.SearchCandidate> selected = selector.select(
                "FAQ 里写的奖励规则和 1.4.1 热修公告是否一致？",
                pool,
                8,
                KnowledgeRetrievalOptions.withDecomposition(
                        false,
                        List.of("FAQ 奖励规则", "1.4.1 热修公告"),
                        "CROSS_DOCUMENT"));

        assertThat(selected.stream().map(KnowledgeVectorStore.SearchCandidate::chunkId))
                .contains(faqGold);
    }

    @Test
    void crossQuestionStillUsesCoverageSelection() {
        assertThat(KnowledgeCoverageAwareSelector.usesCoverageSelection(
                new KnowledgeTitleEntityScoreBooster.QuerySignals(
                        List.of(), List.of(), List.of(), Set.of(), List.of()),
                KnowledgeRetrievalOptions.withDecomposition(false, null, "CROSS_DOCUMENT")))
                .isTrue();
        assertThat(KnowledgeCoverageAwareSelector.usesCoverageSelection(
                new KnowledgeTitleEntityScoreBooster.QuerySignals(
                        List.of(), List.of(), List.of(), Set.of(), List.of()),
                KnowledgeRetrievalOptions.withDecomposition(false, null, "SINGLE_DOCUMENT_FACT")))
                .isFalse();
    }

    private static KnowledgeVectorStore.SearchCandidate candidate(
            UUID documentId, String title, double score, int chunkNo) {
        return candidate(documentId, title, score, chunkNo, UUID.randomUUID());
    }

    private static KnowledgeVectorStore.SearchCandidate candidate(
            UUID documentId, String title, double score, int chunkNo, UUID chunkId) {
        return new KnowledgeVectorStore.SearchCandidate(
                documentId,
                UUID.randomUUID(),
                3,
                chunkId,
                title,
                "content-" + chunkNo,
                score,
                "RELEASE_NOTE",
                "section-" + chunkNo,
                null);
    }
}
