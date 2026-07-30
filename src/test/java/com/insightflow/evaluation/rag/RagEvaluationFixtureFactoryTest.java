package com.insightflow.evaluation.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.insightflow.entity.KnowledgeDocument;
import com.insightflow.entity.KnowledgeDocumentType;
import com.insightflow.entity.KnowledgeDocumentVersion;
import com.insightflow.entity.KnowledgeVersionStatus;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.KnowledgeDocumentRepository;
import com.insightflow.repository.KnowledgeDocumentVersionRepository;
import com.insightflow.service.WorkspaceService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * RAG 评测样本必须只来自当前 Workspace 可见的已发布文档，
 * 不能为了凑题目读取同组织其他游戏的专属资料。
 */
class RagEvaluationFixtureFactoryTest {

    @Test
    void createsCasesOnlyForTemplatesWithAMatchingDocumentAndOneNoKnowledgeCase() {
        UUID workspacePublicId = UUID.randomUUID();
        UUID releaseDocumentId = UUID.randomUUID();
        Workspace workspace = mock(Workspace.class);
        KnowledgeDocument release = document(11L, releaseDocumentId, null, KnowledgeDocumentType.RELEASE_NOTE);
        KnowledgeDocument hiddenIssue = document(12L, UUID.randomUUID(), 99L, KnowledgeDocumentType.KNOWN_ISSUE);
        KnowledgeDocumentVersion releaseVersion = version(UUID.randomUUID(), 1);
        WorkspaceService workspaces = mock(WorkspaceService.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeDocumentVersionRepository versions = mock(KnowledgeDocumentVersionRepository.class);
        when(workspace.getId()).thenReturn(7L);
        when(workspace.getOrganizationId()).thenReturn(3L);
        when(workspaces.get(workspacePublicId)).thenReturn(workspace);
        when(documents.findByOrganizationIdOrderByCreatedAtDesc(3L)).thenReturn(List.of(release, hiddenIssue));
        when(versions.findByDocumentIdAndStatus(11L, KnowledgeVersionStatus.PUBLISHED)).thenReturn(List.of(releaseVersion));

        RagEvaluationFixture fixture = new RagEvaluationFixtureFactory(workspaces, documents, versions)
                .create(workspacePublicId);

        // RELEASE_NOTE 只有一篇已发布文档：只有 documentIndex=0 的两道模板题会生成，
        // 指向 documentIndex=1（次新文档）的第三道模板因缺少第二篇文档而被跳过。
        assertThat(fixture.cases()).extracting(RagEvaluationCaseDefinition::caseId)
                .containsExactly("release-note-1", "release-note-2", "no-knowledge");
        assertThat(fixture.cases().get(0).expectedEvidencePrefixes())
                .containsExactly("knowledge:" + releaseDocumentId + ":");
        assertThat(fixture.cases().get(1).expectedEvidencePrefixes())
                .containsExactly("knowledge:" + releaseDocumentId + ":");
        assertThat(fixture.datasetVersion()).startsWith("rag-gold:v1:");
    }

    @Test
    void exposesTestFixturePurposeNotProductionGate() {
        assertThat(RagEvaluationFixtureFactory.PURPOSE)
                .isEqualTo(RagEvaluationFixturePurpose.TEST_FIXTURE);
    }

    /**
     * 同一类型出现第二篇已发布文档（次新，documentIndex=1）时，
     * 必须新增一道跨文档混淆题，且期望证据要指向次新文档而不是最新文档，
     * 用来验证检索没有把同类型的另一篇文档当成正确来源。
     */
    @Test
    void createsCrossDocumentConfusionCaseWhenASecondDocumentOfTheSameTypeIsPublished() {
        UUID workspacePublicId = UUID.randomUUID();
        UUID newestReleaseId = UUID.randomUUID();
        UUID olderReleaseId = UUID.randomUUID();
        Workspace workspace = mock(Workspace.class);
        // 仓储按创建时间倒序返回；newest 排在前面代表它是最新发布的版本公告。
        KnowledgeDocument newest = document(11L, newestReleaseId, null, KnowledgeDocumentType.RELEASE_NOTE);
        KnowledgeDocument older = document(13L, olderReleaseId, null, KnowledgeDocumentType.RELEASE_NOTE);
        WorkspaceService workspaces = mock(WorkspaceService.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeDocumentVersionRepository versions = mock(KnowledgeDocumentVersionRepository.class);
        KnowledgeDocumentVersion newestVersion = version(UUID.randomUUID(), 2);
        KnowledgeDocumentVersion olderVersion = version(UUID.randomUUID(), 1);
        when(workspace.getId()).thenReturn(7L);
        when(workspace.getOrganizationId()).thenReturn(3L);
        when(workspaces.get(workspacePublicId)).thenReturn(workspace);
        when(documents.findByOrganizationIdOrderByCreatedAtDesc(3L)).thenReturn(List.of(newest, older));
        when(versions.findByDocumentIdAndStatus(11L, KnowledgeVersionStatus.PUBLISHED))
                .thenReturn(List.of(newestVersion));
        when(versions.findByDocumentIdAndStatus(13L, KnowledgeVersionStatus.PUBLISHED))
                .thenReturn(List.of(olderVersion));

        RagEvaluationFixture fixture = new RagEvaluationFixtureFactory(workspaces, documents, versions)
                .create(workspacePublicId);

        assertThat(fixture.cases()).extracting(RagEvaluationCaseDefinition::caseId)
                .containsExactly("release-note-1", "release-note-2", "release-note-3", "no-knowledge");
        assertThat(fixture.cases().get(2).expectedEvidencePrefixes())
                .containsExactly("knowledge:" + olderReleaseId + ":");
    }

    private KnowledgeDocument document(Long id, UUID publicId, Long targetWorkspaceId, KnowledgeDocumentType type) {
        KnowledgeDocument document = mock(KnowledgeDocument.class);
        when(document.getId()).thenReturn(id);
        when(document.getPublicId()).thenReturn(publicId);
        when(document.getTargetWorkspaceId()).thenReturn(targetWorkspaceId);
        when(document.getDocumentType()).thenReturn(type);
        return document;
    }

    private KnowledgeDocumentVersion version(UUID publicId, int versionNo) {
        KnowledgeDocumentVersion version = mock(KnowledgeDocumentVersion.class);
        when(version.getPublicId()).thenReturn(publicId);
        when(version.getVersionNo()).thenReturn(versionNo);
        return version;
    }
}
