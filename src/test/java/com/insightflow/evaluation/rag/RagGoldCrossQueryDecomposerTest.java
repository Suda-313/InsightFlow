package com.insightflow.evaluation.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.insightflow.entity.KnowledgeDocument;
import com.insightflow.entity.RagGoldQuestionType;
import com.insightflow.evaluation.rag.gold.RagGoldEvidenceSnapshot;
import com.insightflow.knowledge.KnowledgeCrossQueryDecomposer;
import com.insightflow.repository.KnowledgeDocumentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 金标 CROSS 子查询：requirement 组 + 文档标题驱动。 */
@ExtendWith(MockitoExtension.class)
class RagGoldCrossQueryDecomposerTest {

    @Mock
    private KnowledgeDocumentRepository documentRepository;

    private RagGoldCrossQueryDecomposer decomposer;

    private static final UUID SIGNIN_DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID GUSHU_DOC = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID FAQ_DOC = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID HOTFIX_DOC = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @BeforeEach
    void setUp() {
        decomposer = new RagGoldCrossQueryDecomposer(documentRepository, new KnowledgeCrossQueryDecomposer());
    }

    @Test
    void buildsDocumentTargetedSubQueriesForConnectorQuestion() {
        stubDocument(SIGNIN_DOC, "超自然行动组 暑期签到活动");
        stubDocument(GUSHU_DOC, "超自然行动组 古蜀遗迹联动活动公告");

        List<String> subQueries = decomposer.buildSubQueries(
                "复盘会上需要确认：暑期签到和古蜀活动的时间窗有没有重叠？各自独立链路吗？",
                RagGoldQuestionType.CROSS_DOCUMENT,
                List.of(
                        evidence("signin-window", SIGNIN_DOC),
                        evidence("gushu-window", GUSHU_DOC)));

        assertThat(subQueries).hasSize(2);
        assertThat(subQueries.get(0)).contains("暑期签到").contains("时间窗");
        assertThat(subQueries.get(1)).contains("古蜀遗迹").contains("时间窗");
    }

    @Test
    void mapsCommaClausesToRequirementGroups() {
        stubDocument(FAQ_DOC, "超自然行动组玩家常见问题FAQ");
        stubDocument(HOTFIX_DOC, "超自然行动组 1.4.1 热修复说明");

        List<String> subQueries = decomposer.buildSubQueries(
                "社区舆情对照：FAQ 说匹配失败怎么办，1.4.1 热修又修了哪个匹配相关问题？",
                RagGoldQuestionType.CROSS_DOCUMENT,
                List.of(
                        evidence("faq-match", FAQ_DOC),
                        evidence("hotfix-141-match", HOTFIX_DOC)));

        assertThat(subQueries).hasSize(2);
        assertThat(subQueries.get(0)).startsWith("社区舆情对照：").contains("FAQ").contains("匹配失败");
        assertThat(subQueries.get(1)).startsWith("社区舆情对照：").contains("1.4.1").contains("热修复说明");
    }

    @Test
    void buildsKiSubQueriesWithoutCrossEntityPollution() {
        UUID ki1301Doc = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0101");
        UUID ki1405Doc = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        stubDocument(ki1301Doc, "超自然行动组-1.3.1-热修复说明");
        stubDocument(ki1405Doc, "超自然行动组-1.4.1-热修复说明");

        List<String> subQueries = decomposer.buildSubQueries(
                "客服转来一个问题：1.3 的 KI-1301 和 1.4.1 的 KI-1405 是同一个根因吗？",
                RagGoldQuestionType.CROSS_DOCUMENT,
                List.of(
                        evidence("ki-1301", ki1301Doc),
                        evidence("ki-1405", ki1405Doc)));

        assertThat(subQueries).hasSize(2);
        assertThat(subQueries.get(0)).contains("KI-1301").doesNotContain("KI-1405");
        assertThat(subQueries.get(1)).contains("KI-1405").contains("热修复说明");
    }

    @Test
    void shortenTitleRemovesGamePrefix() {
        assertThat(RagGoldCrossQueryDecomposer.shortenTitle("超自然行动组-1.4-版本更新说明"))
                .isEqualTo("1.4-版本更新说明");
        assertThat(RagGoldCrossQueryDecomposer.shortenTitle("超自然行动组暑期签到活动运营档案"))
                .isEqualTo("暑期签到活动运营档案");
    }

    private void stubDocument(UUID publicId, String title) {
        KnowledgeDocument document = mock(KnowledgeDocument.class);
        when(document.getTitle()).thenReturn(title);
        when(documentRepository.findByPublicId(publicId)).thenReturn(Optional.of(document));
    }

    private static RagGoldEvidenceSnapshot evidence(String requirementKey, UUID documentPublicId) {
        return new RagGoldEvidenceSnapshot(
                com.insightflow.entity.RagGoldEvidenceGranularity.CHUNK,
                documentPublicId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                requirementKey);
    }
}
