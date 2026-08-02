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
    void extractTitleAnchorsFromFaqSubQuery() {
        List<String> anchors = KnowledgeTitleEntityScoreBooster.extractTitleAnchors(
                "社区舆情对照：玩家常见问题FAQ FAQ 说匹配失败怎么办");
        assertThat(anchors).anyMatch(item -> item.contains("faq"));
    }

    @Test
    void matchesGroupRejectsHotfixChunkForFaqAnchorGroup() {
        KnowledgeTitleEntityScoreBooster.EntityGroup faqGroup = new KnowledgeTitleEntityScoreBooster.EntityGroup(
                List.of("匹配失败", "faq"),
                List.of(),
                List.of(),
                KnowledgeTitleEntityScoreBooster.extractTitleAnchors("玩家常见问题FAQ 匹配失败怎么办"));
        KnowledgeVectorStore.SearchCandidate hotfix = candidate(
                UUID.randomUUID(), "超自然行动组-1.4.1-热修复说明", "修复匹配超时问题");
        assertThat(KnowledgeTitleEntityScoreBooster.matchesGroup(hotfix, faqGroup)).isFalse();
        KnowledgeVectorStore.SearchCandidate faq = candidate(
                UUID.randomUUID(), "超自然行动组玩家常见问题FAQ", "匹配失败请退出重组队");
        assertThat(KnowledgeTitleEntityScoreBooster.matchesGroup(faq, faqGroup)).isTrue();
    }

    private static KnowledgeVectorStore.SearchCandidate candidate(
            UUID documentId, String title, String content) {
        return new KnowledgeVectorStore.SearchCandidate(
                documentId,
                UUID.randomUUID(),
                5,
                UUID.randomUUID(),
                title,
                content,
                0.1,
                "FAQ",
                "section",
                null);
    }

    @Test
    void extractsVersionTokenForTitleBoost() {
        assertThat(KnowledgeTitleEntityScoreBooster.extractVersions("1.4.2 热修 KI-1405")).contains("1.4.2");
        assertThat(KnowledgeTitleEntityScoreBooster.normalizeTitle("超自然行动组-1.4.2-热修复说明"))
                .contains("1.4.2");
    }

    /**
     * Phase 4B 校准门控：identifier=off 时 buildSignals 的 eventIds 必须为空，
     * computeBoost 不对 identifier chunk 加分，保证消融能分离 P2 全链路（supplement + booster）。
     */
    @Test
    void buildSignalsReturnsEmptyEventIdsWhenSupplementDisabled() {
        KnowledgeTitleEntityScoreBooster.QuerySignals signals = booster.buildSignals(
                "KI-1234 和 KI-5678 是否同根因？",
                KnowledgeRetrievalOptions.withDecomposition(false, null, null, false, false));
        assertThat(signals.eventIds()).isEmpty();
    }

    /** identifier=on 时 buildSignals 应提取并汇入所有事件编号，用于 computeBoost 加权。 */
    @Test
    void buildSignalsContainsEventIdsWhenSupplementEnabled() {
        KnowledgeTitleEntityScoreBooster.QuerySignals signals = booster.buildSignals(
                "KI-1234 和 KI-5678 是否同根因？",
                KnowledgeRetrievalOptions.withDecomposition(false, null, null, true, false));
        assertThat(signals.eventIds()).containsExactlyInAnyOrder("KI-1234", "KI-5678");
    }

    /**
     * Phase 4B 门控：identifier=off 时 booster 对子查询中携带事件编号的 chunk 不应施加
     * identifier 分（eventIds 从 group 中也不汇入 signals），排名仍由 RRF + entity 信号决定。
     */
    @Test
    void buildSignalsGroupEventIdsGatedWhenSupplementDisabledWithSubQueries() {
        KnowledgeTitleEntityScoreBooster.QuerySignals signals = booster.buildSignals(
                "调查员笔记：1.3.1 的 KI-1301 和 1.4.1 的 KI-1405 是同一个根因吗？",
                KnowledgeRetrievalOptions.withDecomposition(
                        false,
                        List.of(
                                "调查员笔记：1.3.1 热修复说明 KI-1301",
                                "调查员笔记：1.4.1 热修复说明 KI-1405"),
                        "CROSS_DOCUMENT",
                        false,
                        false));
        // supplement 关闭时，子查询 group 的 eventIds 也不汇入顶层 signals，booster 不加 identifier 分
        assertThat(signals.eventIds()).isEmpty();
        // 但 entityGroups 本身仍包含 eventIds，ensureEntityCoverage/matchesGroup 仍可正常工作
        assertThat(signals.entityGroups()).hasSize(2);
        assertThat(signals.entityGroups().get(0).eventIds()).contains("KI-1301");
        assertThat(signals.entityGroups().get(1).eventIds()).contains("KI-1405");
    }

    @Test
    void boostsCandidateWhenContentContainsEventIdentifier() {
        UUID hotfixDoc = UUID.randomUUID();
        List<KnowledgeVectorStore.SearchCandidate> candidates = List.of(
                candidate(hotfixDoc, "超自然行动组-1.4.1-热修复说明", "无关正文", 0.04),
                candidate(hotfixDoc, "超自然行动组-1.3.1-热修复说明", "| KI-1301 | 机关交互 | 已修复 |", 0.03));

        List<KnowledgeVectorStore.SearchCandidate> boosted = booster.apply(
                "客服转来一个问题：1.3 的 KI-1301 和 1.4.1 的 KI-1405 是同一个根因吗？",
                candidates,
                KnowledgeRetrievalOptions.withDecomposition(
                        false,
                        List.of(
                                "客服转来一个问题：1.3.1 热修复说明 KI-1301",
                                "客服转来一个问题：1.4.1 热修复说明 KI-1405"),
                        "CROSS_DOCUMENT"));

        assertThat(boosted.get(0).content()).contains("KI-1301");
        assertThat(boosted.get(0).score()).isGreaterThan(0.04);
    }

    private static KnowledgeVectorStore.SearchCandidate candidate(UUID documentId, String title, double score) {
        return candidate(documentId, title, "content", score);
    }

    private static KnowledgeVectorStore.SearchCandidate candidate(
            UUID documentId, String title, String content, double score) {
        return new KnowledgeVectorStore.SearchCandidate(
                documentId,
                UUID.randomUUID(),
                3,
                UUID.randomUUID(),
                title,
                content,
                score,
                "OPERATION_EVENT",
                "section",
                null);
    }
}
