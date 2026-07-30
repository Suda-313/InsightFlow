package com.insightflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.insightflow.entity.KnowledgeDocumentType;
import org.junit.jupiter.api.Test;

/** 检索计划器必须覆盖全部固定文档类型，且关键词映射保持确定性。 */
class KnowledgeRetrievalPlannerTest {

    private final KnowledgeRetrievalPlanner planner = new KnowledgeRetrievalPlanner();

    @Test
    void plansOperationEventForMaintenanceKeywords() {
        assertThat(planner.plan("本次停服维护影响了哪些渠道？"))
                .contains(KnowledgeDocumentType.OPERATION_EVENT);
    }

    @Test
    void plansPostmortemForIncidentReviewKeywords() {
        assertThat(planner.plan("请查阅版本事故复盘中的根因分析"))
                .contains(KnowledgeDocumentType.POSTMORTEM);
    }

    @Test
    void keepsExistingTypesCompatible() {
        assertThat(planner.plan("7 月版本公告有哪些已知问题？"))
                .containsExactlyInAnyOrder(
                        KnowledgeDocumentType.RELEASE_NOTE,
                        KnowledgeDocumentType.KNOWN_ISSUE);
    }
}
