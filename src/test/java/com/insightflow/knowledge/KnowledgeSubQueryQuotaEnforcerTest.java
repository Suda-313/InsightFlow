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

    /**
     * Phase 4B 回归场景（dev-154 复现）：子查询 B 的 gold chunk 在本地排名 Top1，
     * 但因仅出现于 B 路检索、全局 RRF 排名靠后（此处模拟为 rankedPool 末位），
     * 同时 B 路还有另一个 chunk（gushuOther）在全局排名靠前但本地排名第 2。
     *
     * <p>旧逻辑（pickBestFromPool 扫描全局池）会选 gushuOther 充当配额，gushuGold 可能不进 Top8。
     * Phase 4B（pickLocalTop 取本地 index=0）直接保留 gushuGold，断言其出现在最终结果中。</p>
     */
    @Test
    void quotaUsesLocalTopOneNotGlobalRanking() {
        UUID signinDoc = UUID.randomUUID();
        UUID gushuDoc = UUID.randomUUID();

        // Sub-query A (signin) 的本地 Top1 gold chunk
        UUID signinGoldChunk = UUID.randomUUID();
        // Sub-query B (gushu) 的本地 Top1 gold chunk（全局 RRF 排名靠后）
        UUID gushuGoldChunk = UUID.randomUUID();
        // gushu 路同 eligible 集合中另一个 chunk，因同时出现在 signin 路而全局排名更靠前
        UUID gushuOtherChunk = UUID.randomUUID();

        // 6 个补充 signin chunk，为 coverage fill 提供足够候选（全局排名 2-7）
        UUID s1 = UUID.randomUUID(), s2 = UUID.randomUUID(), s3 = UUID.randomUUID();
        UUID s4 = UUID.randomUUID(), s5 = UUID.randomUUID(), s6 = UUID.randomUUID();

        // rankedPool：全局 RRF 合并后顺序
        //  - signinGold(0.90) → gushuOther(0.50，跨路高分）→ 6×signin → gushuGold(0.10，仅 gushu 路）
        // 旧逻辑：gushu 配额选 gushuOther（位置 1，在 gushu eligible 集合内）；gushuGold(位置 8) 不入 Top8
        // 新逻辑：gushu 配额直取 candidates.get(0)=gushuGold；gushuGold 必然进入最终 Top8
        List<KnowledgeVectorStore.SearchCandidate> rankedPool = List.of(
                candidate(signinGoldChunk, signinDoc, "超自然行动组暑期签到活动运营档案", 0.90),
                candidate(gushuOtherChunk, gushuDoc, "超自然行动组古蜀遗迹联动活动公告", 0.50),
                candidate(s1, signinDoc, "超自然行动组暑期签到活动运营档案", 0.45),
                candidate(s2, signinDoc, "超自然行动组暑期签到活动运营档案", 0.40),
                candidate(s3, signinDoc, "超自然行动组暑期签到活动运营档案", 0.35),
                candidate(s4, signinDoc, "超自然行动组暑期签到活动运营档案", 0.30),
                candidate(s5, signinDoc, "超自然行动组暑期签到活动运营档案", 0.25),
                candidate(s6, signinDoc, "超自然行动组暑期签到活动运营档案", 0.20),
                candidate(gushuGoldChunk, gushuDoc, "超自然行动组古蜀遗迹联动活动公告", 0.10));

        // Sub-query A：本地 Top1 = signinGold
        KnowledgeSearchResult signinSub = new KnowledgeSearchResult(
                List.of(candidate(signinGoldChunk, signinDoc, "超自然行动组暑期签到活动运营档案", 0.90)),
                Set.of(), Set.of(), Set.of(signinGoldChunk));

        // Sub-query B（gushu）：本地 Top1 = gushuGold（index=0），gushuOther 在本地 index=1
        // 全局池中 gushuOther 排在 gushuGold 之前（分数 0.50 vs 0.10）
        KnowledgeSearchResult gushuSub = new KnowledgeSearchResult(
                List.of(
                        candidate(gushuGoldChunk, gushuDoc, "超自然行动组古蜀遗迹联动活动公告", 0.10),
                        candidate(gushuOtherChunk, gushuDoc, "超自然行动组古蜀遗迹联动活动公告", 0.08)),
                Set.of(), Set.of(), Set.of(gushuGoldChunk, gushuOtherChunk));

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
        // Phase 4B 核心断言：gushu 本地 Top1（gold chunk）必须进入 Top8，
        // 即使它在全局 rankedPool 中排在最后（位置 8，分数 0.10）
        assertThat(selected.stream().map(KnowledgeVectorStore.SearchCandidate::chunkId))
                .as("gushu 子查询本地 Top1 gold chunk 应通过配额被保留，而非被全局靠前的 gushuOther 替代")
                .contains(gushuGoldChunk);
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
