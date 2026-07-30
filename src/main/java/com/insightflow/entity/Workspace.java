package com.insightflow.entity;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Workspace 是所有业务数据的一级隔离边界。
 *
 * <p>数据库内部使用自增 {@code id} 保持关联和索引成本低；HTTP API、事件和 Trace 只使用
 * 时间有序的 UUIDv7 {@code publicId}，避免暴露内部行号，也便于跨系统引用。</p>
 */
@Entity
@Table(name = "workspace")
public class Workspace {

    /**
     * 仅供数据库外键关联的内部主键，绝不能序列化到对外 API。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Workspace 所属组织的内部关系键；P3 的所有 Workspace 都绑定默认组织，P4 才会由受控管理流程选择组织。
     *
     * <p>该字段不能被外部 API 接受或返回；知识检索以它收敛组织通用和当前 Workspace 专属文档。</p>
     */
    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    /**
     * 面向外部的稳定标识；由 UUIDv7 生成器创建，具备按生成时间大致有序的特性。
     */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /**
     * 用户可见的工作区名称；长度限制与 API 契约、数据库字段保持一致。
     */
    @Column(nullable = false, length = 100)
    private String name;

    /**
     * 创建时刻使用带时区时间，不能由业务更新操作修改。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * 绑定的 L1 Topic Pack 标识（对应 pack.toml 的 pack_id）；为 null 时回退全局默认 Pack。
     *
     * <p>仅影响后续新投影使用的 L1 规则来源；历史 {@code feedback_issue_link} 中的
     * canonical_key 不做自动改写——旧 8 类 issue key 与新 topic_* key 并存时由看板按实际数据统计。</p>
     */
    @Column(name = "topic_pack_id", length = 80)
    private String topicPackId;

    /**
     * 供 JPA 反射创建实体使用；业务代码必须调用带名称的构造方法。
     */
    protected Workspace() {
    }

    /**
     * 创建工作区并一次性确定不可变的公开标识和创建时间。
     *
     * <p>UUIDv7 由成熟库生成，避免自行实现时间位、随机位和并发单调性逻辑。</p>
     */
    public Workspace(String name, Long organizationId) {
        this.publicId = UuidCreator.getTimeOrdered();
        this.name = name;
        this.organizationId = organizationId;
        this.createdAt = OffsetDateTime.now();
    }

    /**
     * 返回内部主键，仅供同进程领域与持久化层使用。
     */
    public Long getId() {
        return id;
    }

    /**
     * 返回内部组织关系键，供服务层在保持 Workspace 隔离前提下解析知识文档可见范围。
     */
    public Long getOrganizationId() {
        return organizationId;
    }

    /**
     * 返回可安全暴露到 API、SSE 与 Trace 的公开标识。
     */
    public UUID getPublicId() {
        return publicId;
    }

    /**
     * 返回用户可见名称；名称的校验由 API 层和领域创建流程共同保证。
     */
    public String getName() {
        return name;
    }

    /**
     * 返回不可变的创建时刻，用于审计和稳定排序，不能用内部自增 id 推断业务顺序。
     */
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * 返回当前绑定的 Topic Pack 标识；null 表示使用应用级默认 Pack。
     */
    public String getTopicPackId() {
        return topicPackId;
    }

    /**
     * 绑定或切换 Topic Pack；packId 须已在 {@link com.insightflow.service.analysis.TopicPackRegistry} 中注册。
     */
    public void bindTopicPack(String packId) {
        this.topicPackId = packId;
    }
}
