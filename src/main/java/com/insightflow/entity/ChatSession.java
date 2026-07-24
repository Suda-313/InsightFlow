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
 * 一个工作区内可恢复的 AI 对话会话。
 *
 * <p>内部自增 {@code id} 只用于与消息表关联，HTTP API 只暴露 UUIDv7 {@code publicId}。{@code workspaceId}
 * 是每次读取、写入都必须参与查询的租户隔离键；当前项目还没有成员模型，因此会话的归属边界是工作区而不是个人。</p>
 */
@Entity
@Table(name = "chat_session")
public class ChatSession {

    /** 数据库内部关联主键，禁止返回到客户端。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 面向 API 的稳定会话标识，避免暴露连续的内部行号。 */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /** 强制工作区隔离的内部外键，创建后不允许迁移到其他工作区。 */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 列表中展示的短标题，首条用户消息会替换默认标题。 */
    @Column(nullable = false, length = 100)
    private String title;

    /** 归档而非物理删除，既满足“新建会话”体验，也保留最小审计能力。 */
    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    /** 会话首次创建的不可变时间，用于稳定排序和审计。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 每次写入消息时更新，刷新页面时按此字段恢复最近活动会话。 */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** 仅供 JPA 反射使用；业务代码必须通过 {@link #create(Long)} 创建会话。 */
    protected ChatSession() {
    }

    /**
     * 创建一个默认标题的活动会话。
     *
     * @param workspaceId 已由服务端解析出的内部工作区主键
     * @return 尚未归档、可立即写入消息的会话
     */
    public static ChatSession create(Long workspaceId) {
        ChatSession session = new ChatSession();
        OffsetDateTime now = OffsetDateTime.now();
        session.publicId = UuidCreator.getTimeOrdered();
        session.workspaceId = workspaceId;
        session.title = "新会话";
        session.createdAt = now;
        session.updatedAt = now;
        return session;
    }

    /**
     * 将第一条用户问题压缩为可读标题；标题只用于导航，完整原文仍只保存在消息表中。
     */
    public void updateTitleFromFirstUserMessage(String firstMessage) {
        if ("新会话".equals(title)) {
            String normalized = firstMessage == null ? "" : firstMessage.trim().replaceAll("\\s+", " ");
            if (!normalized.isBlank()) {
                title = normalized.substring(0, Math.min(normalized.length(), 40));
            }
        }
        touch();
    }

    /** 记录会话有新内容，用于恢复最近会话；归档状态不会因新消息被自动撤销。 */
    public void touch() {
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * 归档会话而不删除消息，刷新后的默认会话列表会自然排除此会话。
     */
    public void archive() {
        if (archivedAt == null) {
            archivedAt = OffsetDateTime.now();
            touch();
        }
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public String getTitle() {
        return title;
    }

    public OffsetDateTime getArchivedAt() {
        return archivedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
