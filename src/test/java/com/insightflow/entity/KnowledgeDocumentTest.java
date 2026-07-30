package com.insightflow.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 文档范围领域测试。
 *
 * <p>范围使用空目标 Workspace 表示组织通用，避免首版为了“多个指定游戏可见”而增加授权关联表。</p>
 */
class KnowledgeDocumentTest {

    /** 组织通用文档必须没有目标 Workspace，供同组织的任意游戏 Workspace 查询。 */
    @Test
    void createsOrganizationCommonDocumentWithoutTargetWorkspace() {
        KnowledgeDocument document = KnowledgeDocument.organizationCommon(
                3L, KnowledgeDocumentType.KNOWN_ISSUE, "登录异常处理手册");

        assertThat(document.getOrganizationId()).isEqualTo(3L);
        assertThat(document.getTargetWorkspaceId()).isNull();
        assertThat(document.isOrganizationCommon()).isTrue();
    }

    /** Workspace 专属文档必须携带目标 Workspace，检索 SQL 才能排除同组织其他游戏。 */
    @Test
    void createsWorkspaceScopedDocumentWithTargetWorkspace() {
        KnowledgeDocument document = KnowledgeDocument.workspaceScoped(
                3L, 9L, KnowledgeDocumentType.POSTMORTEM, "1.4 版本复盘");

        assertThat(document.getOrganizationId()).isEqualTo(3L);
        assertThat(document.getTargetWorkspaceId()).isEqualTo(9L);
        assertThat(document.isOrganizationCommon()).isFalse();
        assertThat(document.getDocumentType()).isEqualTo(KnowledgeDocumentType.POSTMORTEM);
    }
}
