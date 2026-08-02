package com.insightflow.repository;

import com.insightflow.entity.KnowledgeDocument;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** P3 文档元数据端口；任何按 public_id 的读取仍须由服务层补充 Workspace 范围校验。 */
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    /** 仅用于服务层已解析 Workspace 后的受控文档读取。 */
    Optional<KnowledgeDocument> findByPublicId(UUID publicId);

    /** 先按组织收敛列表，服务层再过滤当前 Workspace 专属范围。 */
    List<KnowledgeDocument> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);
}
