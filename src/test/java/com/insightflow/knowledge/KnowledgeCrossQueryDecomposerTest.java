package com.insightflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** CROSS 问题分解：场景前缀剥离、逗号/问号/连接词分句。 */
class KnowledgeCrossQueryDecomposerTest {

    private final KnowledgeCrossQueryDecomposer decomposer = new KnowledgeCrossQueryDecomposer();

    @Test
    void decomposesCrossDocumentQuestionOnConnector() {
        List<String> parts = decomposer.decompose(
                "复盘会上需要确认：暑期签到和古蜀活动的时间窗有没有重叠？各自独立链路吗？",
                "CROSS_DOCUMENT");

        assertThat(parts).hasSizeGreaterThanOrEqualTo(2);
        assertThat(parts.get(0)).contains("暑期签到").contains("时间窗");
        assertThat(parts.get(1)).contains("古蜀活动").contains("时间窗");
    }

    @Test
    void splitsCommaSeparatedCrossClauses() {
        List<String> parts = decomposer.decompose(
                "值班追问：1.4 公告说奖励 10 分钟内到账，7 月稳定性复盘里 7/19 那晚怎么回事？",
                "CROSS_DOCUMENT");

        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).contains("1.4 公告").contains("10 分钟");
        assertThat(parts.get(1)).contains("7 月稳定性复盘").contains("7/19");
    }

    @Test
    void doesNotSplitScenePrefixCommunityOpinion() {
        List<String> parts = decomposer.decompose(
                "社区舆情对照：FAQ 说匹配失败怎么办，1.4.1 热修又修了哪个匹配相关问题？",
                "CROSS_DOCUMENT");

        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).startsWith("社区舆情对照：").contains("FAQ");
        assertThat(parts.get(1)).startsWith("社区舆情对照：").contains("1.4.1 热修");
        assertThat(parts).noneMatch(part -> part.equals("社区舆情"));
    }

    @Test
    void keepsSingleDocumentQuestionIntact() {
        List<String> parts = decomposer.decompose(
                "1.4 公告里奖励到账 SLA 是多少？",
                "SINGLE_DOCUMENT_FACT");

        assertThat(parts).containsExactly("1.4 公告里奖励到账 SLA 是多少？");
    }

    @Test
    void alignsPartsToRequirementGroupCount() {
        List<String> parts = decomposer.alignToGroupCount(
                "质量门禁抽查：数据限制说明能否对外报 DAU？7 月稳定性复盘里的 P99 能否引用？",
                "CROSS_DOCUMENT",
                2);

        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).contains("DAU");
        assertThat(parts.get(1)).contains("P99");
    }

    @Test
    void splitBodyForRequirementGroupsAvoidsSharedAspectPollutionForDistinctKi() {
        List<String> parts = decomposer.splitBodyForRequirementGroups(
                "1.3 的 KI-1301 和 1.4.1 的 KI-1405 是同一个根因吗？",
                2);

        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).contains("KI-1301").doesNotContain("KI-1405");
        assertThat(parts.get(1)).contains("KI-1405");
    }

    @Test
    void splitBodyForRequirementGroupsStillEnrichesSharedAspectForSigninGushu() {
        List<String> parts = decomposer.splitBodyForRequirementGroups(
                "暑期签到和古蜀活动的时间窗有没有重叠？各自独立链路吗？",
                2);

        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).contains("时间窗");
        assertThat(parts.get(1)).contains("时间窗");
    }

    @Test
    void extractSharedAspectFromConnectorBody() {
        String aspect = decomposer.extractSharedAspect(
                "暑期签到和古蜀活动的时间窗有没有重叠？各自独立链路吗？");

        assertThat(aspect).contains("时间窗").contains("独立链路");
    }

    @Test
    void stripsScenePrefixBeforeSplitting() {
        var parsed = decomposer.parseQuestion(
                "复盘会上需要确认：古蜀活动公告说双倍碎片，复盘里对 7/19 结算问题怎么定性？");

        assertThat(parsed.scenePrefix()).isEqualTo("复盘会上需要确认：");
        assertThat(parsed.body()).startsWith("古蜀活动公告");
    }
}
