package com.insightflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.entity.KnowledgeDocument;
import com.insightflow.entity.KnowledgeDocumentType;
import com.insightflow.entity.KnowledgeDocumentVersion;
import com.insightflow.entity.Organization;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.KnowledgeDocumentRepository;
import com.insightflow.repository.KnowledgeDocumentVersionRepository;
import com.insightflow.repository.OrganizationRepository;
import com.insightflow.service.WorkspaceService;
import com.insightflow.storage.KnowledgeObjectStorage;
import java.util.UUID;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 知识上传用例测试。
 *
 * <p>上传阶段不调用模型、不生成切片；它只在当前 Workspace 解析组织范围，将校验后的原文保存到对象存储，
 * 并创建不可检索的待审核版本。</p>
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentServiceTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private KnowledgeDocumentRepository documentRepository;
    @Mock private KnowledgeDocumentVersionRepository versionRepository;
    @Mock private KnowledgeObjectStorage objectStorage;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private Workspace workspace;
    @Mock private KnowledgeDocument persistedDocument;
    @Mock private Organization organization;

    /** 组织通用 Markdown 上传应仅创建待审核版本，并把原文件写入受控对象键。 */
    @Test
    void uploadCreatesPendingOrganizationCommonVersionAndStoresOriginal() {
        UUID workspacePublicId = UUID.randomUUID();
        UUID documentPublicId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "release.md", "text/markdown", "# 7 月公告".getBytes());
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(workspace.getOrganizationId()).thenReturn(3L);
        UUID organizationPublicId = UUID.randomUUID();
        when(organizationRepository.findById(3L)).thenReturn(java.util.Optional.of(organization));
        when(organization.getPublicId()).thenReturn(organizationPublicId);
        when(documentRepository.save(any(KnowledgeDocument.class))).thenReturn(persistedDocument);
        when(persistedDocument.getId()).thenReturn(12L);
        when(persistedDocument.getPublicId()).thenReturn(documentPublicId);
        when(versionRepository.save(any(KnowledgeDocumentVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        KnowledgeDocumentVersion version = new KnowledgeDocumentService(
                workspaceService, documentRepository, versionRepository, objectStorage, organizationRepository)
                .upload(workspacePublicId, new KnowledgeDocumentService.UploadCommand(
                        "7 月版本公告", KnowledgeDocumentType.RELEASE_NOTE, true, file, null));

        assertThat(version.getStatus().name()).isEqualTo("PENDING_REVIEW");
        verify(objectStorage).put(eq("knowledge/" + organizationPublicId + "/" + documentPublicId + "/v1/source"),
                any(), eq((long) file.getSize()), eq("text/markdown"));
    }

    /** 上传时可携带语料元数据，并在待审核版本中持久化。 */
    @Test
    void uploadPersistsVersionMetadata() {
        UUID workspacePublicId = UUID.randomUUID();
        UUID documentPublicId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "event.md", "text/markdown", "# 事件".getBytes());
        OffsetDateTime effectiveFrom = OffsetDateTime.parse("2026-07-01T00:00:00+08:00");
        KnowledgeDocumentVersion.VersionMetadata metadata = new KnowledgeDocumentVersion.VersionMetadata(
                "https://example.com/source", effectiveFrom, effectiveFrom, null, "运营组", "仅 TapTap 渠道");
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(workspace.getOrganizationId()).thenReturn(3L);
        when(organizationRepository.findById(3L)).thenReturn(java.util.Optional.of(organization));
        when(organization.getPublicId()).thenReturn(UUID.randomUUID());
        when(documentRepository.save(any(KnowledgeDocument.class))).thenReturn(persistedDocument);
        when(persistedDocument.getId()).thenReturn(12L);
        when(persistedDocument.getPublicId()).thenReturn(documentPublicId);
        when(versionRepository.save(any(KnowledgeDocumentVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        KnowledgeDocumentVersion version = new KnowledgeDocumentService(
                workspaceService, documentRepository, versionRepository, objectStorage, organizationRepository)
                .upload(workspacePublicId, new KnowledgeDocumentService.UploadCommand(
                        "渠道活动", KnowledgeDocumentType.OPERATION_EVENT, true, file, metadata));

        assertThat(version.getSourceUrl()).isEqualTo("https://example.com/source");
        assertThat(version.getOwner()).isEqualTo("运营组");
        assertThat(version.getFactBoundary()).isEqualTo("仅 TapTap 渠道");
        assertThat(version.getEffectiveFrom()).isEqualTo(effectiveFrom);
    }

    /** 向已有文档追加上传时版本号递增，且不创建新的文档行。 */
    @Test
    void uploadVersionAppendsPendingVersionToExistingDocument() {
        UUID workspacePublicId = UUID.randomUUID();
        UUID documentPublicId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "release-v2.md", "text/markdown", "# v2".getBytes());
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(workspace.getOrganizationId()).thenReturn(3L);
        when(workspace.getId()).thenReturn(7L);
        UUID organizationPublicId = UUID.randomUUID();
        when(organizationRepository.findById(3L)).thenReturn(java.util.Optional.of(organization));
        when(organization.getPublicId()).thenReturn(organizationPublicId);
        when(persistedDocument.getId()).thenReturn(12L);
        when(persistedDocument.getPublicId()).thenReturn(documentPublicId);
        when(persistedDocument.getOrganizationId()).thenReturn(3L);
        when(persistedDocument.getTargetWorkspaceId()).thenReturn(7L);
        when(documentRepository.findByPublicId(documentPublicId)).thenReturn(java.util.Optional.of(persistedDocument));
        KnowledgeDocumentVersion previous = KnowledgeDocumentVersion.pending(12L, 1, "k/v1", "c", "v1.md", "text/markdown", 5L);
        when(versionRepository.findTopByDocumentIdOrderByVersionNoDesc(12L)).thenReturn(java.util.Optional.of(previous));
        when(versionRepository.save(any(KnowledgeDocumentVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        KnowledgeDocumentVersion version = new KnowledgeDocumentService(
                workspaceService, documentRepository, versionRepository, objectStorage, organizationRepository)
                .uploadVersion(workspacePublicId, documentPublicId, file, null);

        assertThat(version.getVersionNo()).isEqualTo(2);
        assertThat(version.getStatus().name()).isEqualTo("PENDING_REVIEW");
        verify(documentRepository, org.mockito.Mockito.never()).save(any(KnowledgeDocument.class));
        verify(objectStorage).put(eq("knowledge/" + organizationPublicId + "/" + documentPublicId + "/v2/source"),
                any(), eq((long) file.getSize()), eq("text/markdown"));
    }
}
