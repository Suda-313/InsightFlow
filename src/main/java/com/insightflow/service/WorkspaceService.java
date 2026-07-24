package com.insightflow.service;

import com.insightflow.entity.Workspace;
import com.insightflow.entity.Organization;
import com.insightflow.common.exception.WorkspaceNotFoundException;
import com.insightflow.repository.OrganizationRepository;
import com.insightflow.repository.WorkspaceRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workspace 用例层，隔离 HTTP 输入与 JPA 细节。
 *
 * <p>后续导入、分析、告警和 Agent Run 都必须先在这里或对应的用例层取得工作区，
 * 再执行数据访问，不能把工作区隔离判断散落到 Controller。</p>
 */
@Service
@Transactional(readOnly = true)
public class WorkspaceService {

    /**
     * Spring Data 仓储是此用例唯一的数据访问入口，便于以后替换或加入审计。
     */
    private final WorkspaceRepository workspaceRepository;

    /**
     * P3 仅用默认组织建立 Workspace 与企业知识的共同归属，不向外部请求暴露可伪造的 organizationId。
     */
    private final OrganizationRepository organizationRepository;

    /**
     * 通过构造器注入依赖，确保服务可被单元测试替换为 mock。
     */
    public WorkspaceService(
            WorkspaceRepository workspaceRepository,
            OrganizationRepository organizationRepository) {
        this.workspaceRepository = workspaceRepository;
        this.organizationRepository = organizationRepository;
    }

    /**
     * 创建工作区。
     *
     * <p>名称在写库前去除首尾空格，避免同一可见名称因无意义空白而产生不同记录；
     * 事务边界只覆盖此次创建，不承担后续导入或分析任务。</p>
     */
    @Transactional
    public Workspace create(String name) {
        Organization organization = organizationRepository.findByDefaultOrganizationTrue()
                .orElseThrow(() -> new IllegalStateException("默认组织不存在，拒绝创建无归属工作区"));
        return workspaceRepository.save(new Workspace(name.trim(), organization.getId()));
    }

    /**
     * 获取一个对外公开的工作区；不存在时抛出领域异常而不是让仓储空值流向 API。
     */
    public Workspace get(UUID publicId) {
        return workspaceRepository.findByPublicId(publicId)
                .orElseThrow(() -> new WorkspaceNotFoundException(publicId));
    }

    public List<Workspace> listAll() {
        return workspaceRepository.findAllByOrderByCreatedAtDesc();
    }
}
