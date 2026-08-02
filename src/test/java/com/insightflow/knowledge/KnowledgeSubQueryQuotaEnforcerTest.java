package com.insightflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Phase 3：多路子查询最低配额后再做覆盖贪心。 */
class KnowledgeSubQueryQuotaEnforcerTest {

    private KnowledgeSubQueryQuotaEnforcer enforcer;
    private KnowledgeCoverageAwareSelector coverageSelector;

    @BeforeEach
    void setUp() {
        KnowledgeCrossQueryDecomposer decomposer = new KnowledgeCrossQueryDecomposer();
        KnowledgeTitleEntityScoreBooster booster = new KnowledgeTitleEntityScoreBooster(decomposer);
        enforcer = new KnowledgeSubQueryQuotaEnforcer(booster);
        coverageSelector = new KnowledgeCoverageAwareSelector(booster);
    }

    @Test
    void reservesOneSlotPerSubQueryBeforeCoverageFill() {
        UUID signinDoc = UUID.randomUUID();
        UUID gushuDoc = UUID.randomUUID();
        UUID signinChunkA = UUID.randomUUID();
        UUID signinChunkB = UUID.randomUUID();
        UUID gushuChunk = UUID.randomUUID();

        List<KnowledgeVectorStore.SearchCandidate> rankedPool = List.of(
                candidate(signinChunkA, signinDoc, "超自然行动组暑期签到活动运营档案", 0.20),
                candidate(signinChunkB, signinDoc, "超自然行动组暑期签到活动运营档案", 0.19),
                candidate(signinChunkB, signinDoc, "超自然行动组暑期签到活动运营档案", 0.18),
                candidate(signinChunkB, signinDoc, "超自然行动组暑期签到活动运营档案", 0.17),
                candidate(signinChunkB, signinDoc, "超自然行动组暑期签到活动运营档案", 0.16),
                candidate(signinChunkB, signinDoc, "超自然行动组暑期签到活动运营档案", 0.15),
                candidate(signinChunkB, signinDoc, "超自然行动组暑期签到活动运营档案", 0.14),
                candidate(signinChunkB, signinDoc, "超自然行动组暑期签到活动运营档案", 0.13),
                candidate(gushuChunk, gushuDoc, "超自然行动组古蜀遗迹联动活动公告", 0.05));

        KnowledgeSearchResult signinSub = new KnowledgeSearchResult(
                List.of(
                        candidate(signinChunkA, signinDoc, "超自然行动组暑期签到活动运营档案", 0.20),
                        candidate(signinChunkB, signinDoc, "超自然行动组暑期签到活动运营档案", 0.19)),
                Set.of(),
                Set.of(),
                Set.of(signinChunkA, signinChunkB));
        KnowledgeSearchResult gushuSub = new KnowledgeSearchResult(
                List.of(candidate(gushuChunk, gushuDoc, "超自然行动组古蜀遗迹联动活动公告", 0.05)),
                Set.of(),
                Set.of(),
                Set.of(gushuChunk));

        List<KnowledgeVectorStore.SearchCandidate> selected = enforcer.selectTopEvidence(
                "复盘会上需要确认：暑期签到和古蜀活动的时间窗有没有重叠？",
                rankedPool,
                8,
                KnowledgeRetrievalOptions.withDecomposition(
                        false,
                        List.of(
                                "复盘会上需要确认：暑期签到的时间窗",
                                "复盘会上需要确认：古蜀遗迹联动活动公告 古蜀活动的时间窗"),
                        "CROSS_DOCUMENT"),
                List.of(signinSub, gushuSub),
                coverageSelector);

        assertThat(selected).hasSize(8);
        assertThat(selected.stream().map(KnowledgeVectorStore.SearchCandidate::documentId).distinct())
                .contains(signinDoc, gushuDoc);
    }

    /** Phase 4C：最弱子查询先占配额；共享 chunk 时强势路不会挤掉弱势路的 boosted 最佳 eligible。 */
    @Test
    void weakestSubQueryFirstReservesDistinctHighValueChunks() {
        UUID strongDoc = UUID.randomUUID();
        UUID weakDoc = UUID.randomUUID();
        UUID sharedChunk = UUID.randomUUID();
        UUID weakOnlyChunk = UUID.randomUUID();

        List<KnowledgeVectorStore.SearchCandidate> rankedPool = List.of(
                candidate(sharedChunk, strongDoc, "强势路文档", 0.90),
                candidate(UUID.randomUUID(), strongDoc, "强势路文档", 0.85),
                candidate(UUID.randomUUID(), strongDoc, "强势路文档", 0.80),
                candidate(UUID.randomUUID(), strongDoc, "强势路文档", 0.75),
                candidate(UUID.randomUUID(), strongDoc, "强势路文档", 0.70),
                candidate(UUID.randomUUID(), strongDoc, "强势路文档", 0.65),
                candidate(weakOnlyChunk, weakDoc, "弱势路公告", 0.12),
                candidate(sharedChunk, strongDoc, "强势路文档", 0.10));

        KnowledgeSearchResult strongSub = new KnowledgeSearchResult(
                List.of(
                        candidate(sharedChunk, strongDoc, "强势路文档", 0.90),
                        candidate(UUID.randomUUID(), strongDoc, "强势路文档", 0.85)),
                Set.of(), Set.of(), Set.of(sharedChunk));
        KnowledgeSearchResult weakSub = new KnowledgeSearchResult(
                List.of(
                        candidate(weakOnlyChunk, weakDoc, "弱势路公告", 0.12),
                        candidate(sharedChunk, strongDoc, "强势路文档", 0.10)),
                Set.of(), Set.of(), Set.of(weakOnlyChunk, sharedChunk));

        List<KnowledgeVectorStore.SearchCandidate> selected = enforcer.selectTopEvidence(
                "复盘会上需要确认：强势活动与弱势公告的时间窗有没有重叠？",
                rankedPool,
                8,
                KnowledgeRetrievalOptions.withDecomposition(
                        false,
                        List.of("强势活动时间窗", "弱势路公告 弱势公告时间窗"),
                        "CROSS_DOCUMENT"),
                List.of(strongSub, weakSub),
                coverageSelector);

        assertThat(selected.stream().map(KnowledgeVectorStore.SearchCandidate::chunkId))
                .contains(weakOnlyChunk);
    }

    @Test
    void singleSubQueryDelegatesToCoverageSelector() {
        UUID doc = UUID.randomUUID();
        List<KnowledgeVectorStore.SearchCandidate> pool = List.of(
                candidate(UUID.randomUUID(), doc, "title-a", 0.9),
                candidate(UUID.randomUUID(), doc, "title-a", 0.8));
        KnowledgeSearchResult single = new KnowledgeSearchResult(pool, Set.of(), Set.of(), Set.of());

        List<KnowledgeVectorStore.SearchCandidate> selected = enforcer.selectTopEvidence(
                "question",
                pool,
                2,
                KnowledgeRetrievalOptions.withDecomposition(false, null, "CROSS_DOCUMENT"),
                List.of(single),
                coverageSelector);

        assertThat(selected).hasSize(2);
        assertThat(selected.get(0).score()).isGreaterThan(selected.get(1).score());
    }

    private static KnowledgeVectorStore.SearchCandidate candidate(
            UUID chunkId, UUID documentId, String title, double score) {
        return new KnowledgeVectorStore.SearchCandidate(
                documentId,
                UUID.randomUUID(),
                3,
                chunkId,
                title,
                "content",
                score,
                "RELEASE_NOTE",
                "section",
                null);
    }
}
