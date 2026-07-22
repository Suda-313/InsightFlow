package com.insightflow.repository;

import com.insightflow.entity.KnowledgeEntry;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 知识库条目持久化端口。 */
public interface KnowledgeEntryRepository extends JpaRepository<KnowledgeEntry, Long> {

    /**
     * 按工作区与分类查询知识库条目。
     *
     * @param workspaceId 工作区 ID
     * @param category    分类标签
     * @return 匹配的知识库条目列表
     */
    List<KnowledgeEntry> findByWorkspaceIdAndCategory(Long workspaceId, String category);

    /**
     * 按工作区查询所有知识库条目。
     *
     * @param workspaceId 工作区 ID
     * @return 该工作区下的所有知识库条目
     */
    List<KnowledgeEntry> findByWorkspaceId(Long workspaceId);
}