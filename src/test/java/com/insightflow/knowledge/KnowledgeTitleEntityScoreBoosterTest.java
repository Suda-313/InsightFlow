package com.insightflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** P2 标题/实体加权：精排前候选保护与双主体覆盖。 */
class KnowledgeTitleEntityScoreBoosterTest {

    private KnowledgeTitleEntityScoreBooster booster;

    @BeforeEach
    void setUp() {
        booster = new KnowledgeTitleEntityScoreBooster(new KnowledgeCrossQueryDecomposer(), 8);
    }

    @Test
    void boostsCandidateWhenTitleContainsQueryEntity() {
        UUID gushuDoc = UUID.randomUUID();
        UUID sopDoc = UUID.randomUUID();
        List<KnowledgeVectorStore.SearchCandidate> candidates = List.of(
                candidate(sopDoc, "超自然行动组版本窗口反馈归因 SOP", 0.05),
                candidate(gushuDoc, "超自然行动组古蜀遗迹联动活动公告", 0.04));

        List<KnowledgeVectorStore.SearchCandidate> boosted = booster.apply(
                "复盘会上需要确认：暑期签到和古蜀活动的时间窗有没有重叠？",
                candidates,
                KnowledgeRetrievalOptions.withDecomposition(
                        false,
                        List.of(
                                "复盘会上需要确认：暑期签到的时间窗有没有重叠",
                                "复盘会上需要确认：古蜀活动的时间窗有没有重叠"),
                        "CROSS_DOCUMENT"));

        assertThat(boosted.get(0).documentId()).isEqualTo(gushuDoc);
        assertThat(boosted.get(0).score()).isGreaterThan(0.04);
    }

    @Test
    void promotesMissingEntityIntoTopEightForDualAspectQuery() {
        UUID signinDoc = UUID.randomUUID();
        UUID gushuDoc = UUID.randomUUID();
        List<KnowledgeVectorStore.SearchCandidate> candidates = List.of(
                candidate(signinDoc, "超自然行动组暑期签到活动运营档案", 0.10),
                candidate(signinDoc, "超自然行动组暑期签到活动运营档案", 0.09),
                candidate(signinDoc, "超自然行动组暑期签到活动运营档案", 0.08),
                candidate(signinDoc, "超自然行动组暑期签到活动运营档案", 0.07),
                candidate(signinDoc, "超自然行动组暑期签到活动运营档案", 0.06),
                candidate(signinDoc, "超自然行动组暑期签到活动运营档案", 0.05),
                candidate(signinDoc, "超自然行动组暑期签到活动运营档案", 0.04),
                candidate(signinDoc, "超自然行动组暑期签到活动运营档案", 0.03),
                candidate(gushuDoc, "超自然行动组古蜀遗迹联动活动公告", 0.02));

        List<KnowledgeVectorStore.SearchCandidate> boosted = booster.apply(
                "复盘会上需要确认：暑期签到和古蜀活动的时间窗有没有重叠？",
                candidates,
                KnowledgeRetrievalOptions.withDecomposition(
                        false,
                        List.of(
                                "复盘会上需要确认：暑期签到的时间窗",
                                "复盘会上需要确认：古蜀遗迹联动活动公告 古蜀活动的时间窗"),
                        "CROSS_DOCUMENT"));

        List<KnowledgeVectorStore.SearchCandidate> topEight = boosted.subList(0, 8);
        assertThat(topEight.stream().anyMatch(item -> item.documentId().equals(gushuDoc))).isTrue();
        assertThat(topEight.stream().anyMatch(item -> item.documentId().equals(signinDoc))).isTrue();
    }

    @Test
    void extractsVersionTokenForTitleBoost() {
        assertThat(KnowledgeTitleEntityScoreBooster.extractVersions("1.4.2 热修 KI-1405")).contains("1.4.2");
        assertThat(KnowledgeTitleEntityScoreBooster.normalizeTitle("超自然行动组-1.4.2-热修复说明"))
                .contains("1.4.2");
    }

    private static KnowledgeVectorStore.SearchCandidate candidate(UUID documentId, String title, double score) {
        return new KnowledgeVectorStore.SearchCandidate(
                documentId,
                UUID.randomUUID(),
                3,
                UUID.randomUUID(),
                title,
                "content",
                score,
                "OPERATION_EVENT",
                "section",
                null);
    }
}
