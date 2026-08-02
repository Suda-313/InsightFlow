package com.insightflow.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.entity.KnowledgeDocumentType;
import com.insightflow.entity.KnowledgeDocumentVersion;
import com.insightflow.knowledge.KnowledgeDocumentService;
import com.insightflow.knowledge.KnowledgePublishingService;
import com.insightflow.security.WorkspaceAccessService;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 企业知识管理接口的边界测试。
 * 测试聚焦范围参数和内部来源下载契约；实际文档归属校验仍由知识用例服务统一完成。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentControllerTest {

    @Mock private KnowledgeDocumentService documents;
    @Mock private KnowledgePublishingService publishing;
    @Mock private WorkspaceAccessService workspaceAccess;

    /** 只有两种显式范围可提交，未知字符串不能被悄悄降级成当前 Workspace 专属。 */
    @Test
    void rejectsUnknownKnowledgeScopeBeforeCallingUploadService() {
        KnowledgeDocumentController controller = new KnowledgeDocumentController(documents, publishing, workspaceAccess);
        MockMultipartFile file = new MockMultipartFile("file", "notice.md", "text/markdown", "# 公告".getBytes());

        assertThatThrownBy(() -> controller.upload(UUID.randomUUID(), "公告", KnowledgeDocumentType.RELEASE_NOTE,
                "ALL_WORKSPACES", file, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope");
        verify(documents, never()).upload(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /** 来源读取必须通过应用接口返回带文件名的附件头，不能将 MinIO 对象地址泄露给浏览器。 */
    @Test
    void streamsSourceWithSafeAttachmentFilename() {
        UUID workspaceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(documents.openSource(workspaceId, documentId, versionId)).thenReturn(
                new KnowledgeDocumentService.SourceView("notice.md", "text/markdown", 4,
                        new ByteArrayInputStream("公告".getBytes())));

        var response = new KnowledgeDocumentController(documents, publishing, workspaceAccess)
                .source(workspaceId, documentId, versionId);

        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .contains("attachment")
                .contains("notice.md");
        verify(workspaceAccess).requireRead(workspaceId);
    }

    /** 追加上传走独立端点，只接受文件且不重复创建文档。 */
    @Test
    void uploadVersionDelegatesToDocumentService() {
        UUID workspaceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "notice-v2.md", "text/markdown", "# v2".getBytes());
        KnowledgeDocumentVersion version = KnowledgeDocumentVersion.pending(1L, 2, "k/v2", "c", "notice-v2.md", "text/markdown", 4L);
        when(documents.uploadVersion(workspaceId, documentId, file, null)).thenReturn(version);

        var response = new KnowledgeDocumentController(documents, publishing, workspaceAccess)
                .uploadVersion(workspaceId, documentId, file, null, null, null, null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().versionNo()).isEqualTo(2);
        verify(workspaceAccess).requireRead(workspaceId);
    }

    /** 发布默认保留旧版；请求体显式为 true 时才下线旧版。 */
    @Test
    void publishPassesExpirePreviousFlagToPublishingService() {
        UUID workspaceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        KnowledgeDocumentVersion version = KnowledgeDocumentVersion.pending(1L, 2, "k/v2", "c", "x.md", "text/markdown", 4L);
        when(publishing.publish(workspaceId, documentId, versionId, true)).thenReturn(version);

        var response = new KnowledgeDocumentController(documents, publishing, workspaceAccess)
                .publish(workspaceId, documentId, versionId, new KnowledgeDocumentController.PublishRequest(true));

        assertThat(response.versionNo()).isEqualTo(2);
        verify(publishing).publish(workspaceId, documentId, versionId, true);
    }
}
