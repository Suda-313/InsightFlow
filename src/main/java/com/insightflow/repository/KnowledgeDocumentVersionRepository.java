package com.insightflow.repository;

import com.insightflow.entity.KnowledgeDocumentVersion;
import com.insightflow.entity.KnowledgeVersionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 知识版本端口；版本不能脱离其文档和 Workspace 范围单独被 API 查询。 */
public interface KnowledgeDocumentVersionRepository extends JpaRepository<KnowledgeDocumentVersion, Long> {

    /** 为同一文档分配单调递增版本号。 */
    Optional<KnowledgeDocumentVersion> findTopByDocumentIdOrderByVersionNoDesc(Long documentId);

    /** 服务层必须同时校验 document_id，避免只凭 UUID 跨文档读取版本。 */
    Optional<KnowledgeDocumentVersion> findByPublicIdAndDocumentId(UUID publicId, Long documentId);

    /** 发布新版本前读取当前已发布版本，服务层会在同一事务中将其失效。 */
    List<KnowledgeDocumentVersion> findByDocumentIdAndStatus(Long documentId, KnowledgeVersionStatus status);

    /** 管理页面只读取同一文档的版本历史，按最新版本优先展示。 */
    List<KnowledgeDocumentVersion> findByDocumentIdOrderByVersionNoDesc(Long documentId);
}
