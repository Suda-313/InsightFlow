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
    void createsOnePublishedCasePerVisibleDocumentTypeAndOneNoKnowledgeCase() {
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

        assertThat(fixture.cases()).extracting(RagEvaluationCaseDefinition::caseId)
                .containsExactly("release-note", "no-knowledge");
        assertThat(fixture.cases().get(0).expectedEvidencePrefixes())
                .containsExactly("knowledge:" + releaseDocumentId + ":");
        assertThat(fixture.datasetVersion()).startsWith("rag-gold:v1:");
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
