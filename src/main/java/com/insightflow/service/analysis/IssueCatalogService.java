package com.insightflow.service.analysis;

import com.insightflow.entity.IssueAlias;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.repository.IssueAliasRepository;
import com.insightflow.repository.IssueCatalogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workspace 私有主题目录的 find-or-create 服务。
 *
 * <p>设计动机：规则命中后必须能"查或建"同一主题目录，但绝不允许对同一
 * {@code (workspaceId, canonicalKey)} 重复创建——canonical_key 是 Workspace 内
 * 稳定业务键，重复创建会割裂 feedback_issue_link / cell_issue 的归因链路。
 * 因此 findOrCreate 先按 Workspace 隔离查询，命中则只刷新末次出现时间，
 * 不命中才新建 active 主题。</p>
 *
 * <p>别名侧同理：origin="rule" 的别名对同一 {@code (workspaceId, normalizedAlias)}
 * 只记录一次，防止规则反复命中时重复写别名；未来 LLM 建议别名通过同一张
 * issue_alias 表、不同 origin 列追溯，绝不自行改写统计结果。</p>
 *
 * <p>事务策略：类级 {@code @Transactional}（默认 REQUIRED 传播）。若调用方已开
 * 事务（如 Task 10 的 REQUIRES_NEW 执行事务），本服务 JOIN 之，touchLastSeen
 * 的改动随调用方提交一并 flush；若被独立调用，则自建事务，保证 touchLastSeen
 * 持久化、save 触发的 IDENTITY INSERT 立即可见（Task 9 FactWriter 依赖
 * findOrCreate(...).getId() 非 null）。所有查询均携带 workspaceId，维持
 * Workspace 一级租户隔离不变量。</p>
 */
@Service
@Transactional
public class IssueCatalogService {

    /** 主题目录仓储，按 workspace + canonical_key 查找以实现幂等 find-or-create。 */
    private final IssueCatalogRepository catalogRepository;
    /** 别名仓储，按 workspace + normalized_alias 判重，防规则重复写别名。 */
    private final IssueAliasRepository aliasRepository;

    /** 构造目录服务；两个仓储均为 Workspace 隔离查询。 */
    public IssueCatalogService(IssueCatalogRepository catalogRepository, IssueAliasRepository aliasRepository) {
        this.catalogRepository = catalogRepository;
        this.aliasRepository = aliasRepository;
    }

    /**
     * 按 Workspace 隔离的 find-or-create：命中既有目录则只刷新末次出现时间
     * （由类级事务保证持久化，无需显式 save），不命中则新建 active 主题并保存。
     *
     * <p>幂等性：既有主题绝不重复创建，避免割裂归因链路。touchLastSeen 仅在
     * 命中分支调用，既有主题保持原 id / publicId 不变。</p>
     *
     * @param workspaceId   一级租户隔离键
     * @param canonicalKey  稳定主题键，与规则 canonical_key 一致
     * @param canonicalName 用户可读主题名
     * @return 命中的既有目录（已刷新 lastSeen）或新建的 active 目录
     */
    public IssueCatalog findOrCreate(Long workspaceId, String canonicalKey, String canonicalName) {
        return catalogRepository.findByWorkspaceIdAndCanonicalKey(workspaceId, canonicalKey)
                .map(catalog -> {
                    catalog.touchLastSeen();
                    return catalog;
                })
                .orElseGet(() -> catalogRepository.save(IssueCatalog.create(workspaceId, canonicalKey, canonicalName)));
    }

    /**
     * 记录规则来源别名：同一 {@code (workspaceId, normalizedAlias)} 只写一次。
     *
     * <p>origin 固定为 "rule"，与未来 LLM/人工别名（同表不同 origin）区分；
     * 规则别名仅作追溯，不自行改写统计。先 existsBy 判重再 save，避免重复 INSERT。</p>
     *
     * @param workspaceId     一级租户隔离键
     * @param issueId         关联主题目录内部主键
     * @param normalizedAlias 归一化后的别名文本
     */
    public void recordAliasIfNeeded(Long workspaceId, Long issueId, String normalizedAlias) {
        if (!aliasRepository.existsByWorkspaceIdAndNormalizedAlias(workspaceId, normalizedAlias)) {
            aliasRepository.save(IssueAlias.ruleAlias(workspaceId, issueId, normalizedAlias));
        }
    }
}
