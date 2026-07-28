package com.insightflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

import com.insightflow.entity.KnowledgeDocument;
import com.insightflow.entity.KnowledgeDocumentVersion;
import com.insightflow.entity.KnowledgeDocumentType;
import com.insightflow.entity.KnowledgeVersionStatus;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.KnowledgeDocumentRepository;
import com.insightflow.repository.KnowledgeDocumentVersionRepository;
import com.insightflow.service.WorkspaceService;
import com.insightflow.storage.KnowledgeObjectStorage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 发布服务必须在单一受控事务内完成版本替换、切片和嵌入写入。 */
@ExtendWith(MockitoExtension.class)
class KnowledgePublishingServiceTest {
    @Mock private WorkspaceService workspaceService;
    @Mock private KnowledgeDocumentRepository documentRepository;
    @Mock private KnowledgeDocumentVersionRepository versionRepository;
    @Mock private KnowledgeObjectStorage objectStorage;
    @Mock private KnowledgeEmbeddingGateway embeddingGateway;
    @Mock private KnowledgeVectorStore vectorStore;
    @Mock private Workspace workspace;
    @Mock private KnowledgeDocument document;

    /** 新版本发布后旧版本失效，且只有嵌入完成的切片才会交给向量存储。 */
    @Test
    void publishesPendingVersionAndExpiresPreviousPublishedVersion() {
        UUID workspaceId = UUID.randomUUID(); UUID documentId = UUID.randomUUID(); UUID versionId = UUID.randomUUID();
        KnowledgeDocumentVersion pending = KnowledgeDocumentVersion.pending(10L, 2, "k/v2", "c", "x.md", "text/markdown", 10L);
        assignId(pending, 10L);
        KnowledgeDocumentVersion previous = KnowledgeDocumentVersion.pending(10L, 1, "k/v1", "c", "x.md", "text/markdown", 10L);
        previous.publish(java.time.OffsetDateTime.now());
        when(workspaceService.get(workspaceId)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(4L); when(workspace.getOrganizationId()).thenReturn(3L);
        when(documentRepository.findByPublicId(documentId)).thenReturn(Optional.of(document));
        when(document.getId()).thenReturn(10L); when(document.getOrganizationId()).thenReturn(3L); when(document.getTargetWorkspaceId()).thenReturn(4L);
        when(document.getTitle()).thenReturn("登录异常说明");
        when(document.getDocumentType()).thenReturn(KnowledgeDocumentType.KNOWN_ISSUE);
        when(versionRepository.findByPublicIdAndDocumentId(versionId, 10L)).thenReturn(Optional.of(pending));
        when(versionRepository.findByDocumentIdAndStatus(10L, KnowledgeVersionStatus.PUBLISHED)).thenReturn(List.of(previous));
        when(objectStorage.open("k/v2")).thenReturn(new ByteArrayInputStream("# 公告\n\n内容".getBytes()));
        when(embeddingGateway.embed(any())).thenReturn(List.of(List.of(0.1d, 0.2d)));

        KnowledgeDocumentVersion result = new KnowledgePublishingService(workspaceService, documentRepository, versionRepository,
                objectStorage, new KnowledgeChunker(100), embeddingGateway, vectorStore).publish(workspaceId, documentId, versionId);

        assertThat(result.getStatus()).isEqualTo(KnowledgeVersionStatus.PUBLISHED);
        assertThat(previous.getStatus()).isEqualTo(KnowledgeVersionStatus.EXPIRED);

        ArgumentCaptor<List<String>> embedCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingGateway).embed(embedCaptor.capture());
        assertThat(embedCaptor.getValue()).containsExactly(
                "[登录异常说明 | KNOWN_ISSUE | v2]\n## 公告\n内容");

        ArgumentCaptor<List<KnowledgeVectorStore.EmbeddedChunk>> storeCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).store(eq(10L), storeCaptor.capture());
        assertThat(storeCaptor.getValue()).extracting(KnowledgeVectorStore.EmbeddedChunk::content)
                .containsExactly("内容");
    }

    /**
     * 非待审核版本在读取原文和调用嵌入模型前就必须被拒绝，
     * 否则无效的发布请求会无意义消耗存储与模型资源。
     */
    @Test
    void rejectsNonPendingVersionBeforeReadingOrEmbeddingKnowledge() {
        UUID workspaceId = UUID.randomUUID(); UUID documentId = UUID.randomUUID(); UUID versionId = UUID.randomUUID();
        KnowledgeDocumentVersion expired = KnowledgeDocumentVersion.pending(10L, 1, "k/v1", "c", "x.md", "text/markdown", 10L);
        expired.publish(java.time.OffsetDateTime.now());
        expired.expire(java.time.OffsetDateTime.now());
        when(workspaceService.get(workspaceId)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(4L); when(workspace.getOrganizationId()).thenReturn(3L);
        when(documentRepository.findByPublicId(documentId)).thenReturn(Optional.of(document));
        when(document.getId()).thenReturn(10L); when(document.getOrganizationId()).thenReturn(3L); when(document.getTargetWorkspaceId()).thenReturn(4L);
        when(versionRepository.findByPublicIdAndDocumentId(versionId, 10L)).thenReturn(Optional.of(expired));

        KnowledgePublishingService service = new KnowledgePublishingService(workspaceService, documentRepository, versionRepository,
                objectStorage, new KnowledgeChunker(100), embeddingGateway, vectorStore);

        assertThatThrownBy(() -> service.publish(workspaceId, documentId, versionId)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(objectStorage, embeddingGateway, vectorStore);
    }

    /** JPA 在上传持久化后分配版本内部键；单元测试不连接数据库，显式模拟该已持久化前置条件。 */
    private void assignId(KnowledgeDocumentVersion version, Long id) {
        try {
            var field = KnowledgeDocumentVersion.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(version, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
