package com.insightflow.service;

import com.insightflow.entity.KnowledgeEntry;
import com.insightflow.repository.KnowledgeEntryRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库用例层，封装知识条目管理的业务逻辑。
 *
 * <p>当前阶段使用 LIKE 模糊搜索，后续切换为 pgvector 向量搜索。</p>
 */
@Service
@Transactional(readOnly = true)
public class KnowledgeService {

    private final KnowledgeEntryRepository knowledgeEntryRepository;

    /**
     * 通过构造器注入依赖，确保服务可被单元测试替换为 mock。
     */
    public KnowledgeService(KnowledgeEntryRepository knowledgeEntryRepository) {
        this.knowledgeEntryRepository = knowledgeEntryRepository;
    }

    /**
     * 插入一条知识库条目。
     *
     * @param workspaceId 工作区 ID
     * @param title       条目标题
     * @param content     条目正文
     * @param category    分类标签
     * @param source      来源
     * @return 持久化后的知识库条目
     */
    @Transactional
    public KnowledgeEntry insert(Long workspaceId, String title, String content,
                                  String category, String source) {
        KnowledgeEntry entry = KnowledgeEntry.create(
                workspaceId, title, content, category, source, null);
        return knowledgeEntryRepository.save(entry);
    }

    /**
     * 搜索知识库条目（当前用 LIKE 模糊搜索，后续切换向量搜索）。
     *
     * @param workspaceId 工作区 ID
     * @param query       搜索关键词
     * @return 匹配的知识库条目列表
     */
    public List<KnowledgeEntry> search(Long workspaceId, String query) {
        return knowledgeEntryRepository.findByWorkspaceId(workspaceId).stream()
                .filter(e -> e.getTitle().contains(query) || e.getContent().contains(query))
                .toList();
    }

    /**
     * 按分类列出知识库条目。
     *
     * @param workspaceId 工作区 ID
     * @param category    分类标签
     * @return 匹配的知识库条目列表
     */
    public List<KnowledgeEntry> listByCategory(Long workspaceId, String category) {
        return knowledgeEntryRepository.findByWorkspaceIdAndCategory(workspaceId, category);
    }
}