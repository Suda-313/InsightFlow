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
 * 会话中的一条最终可展示消息。
 *
 * <p>消息冗余保存 {@code workspaceId}，使每个读取请求都可在 SQL 层同时按工作区和会话过滤。只保存用户输入和
 * 模型最终答案，不保存原始思维链、工具内部参数或模型元数据。</p>
 */
@Entity
@Table(name = "chat_message")
public class ChatMessage {

    /** 内部关联主键，仅供 JPA 和会话外键使用。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对外展示消息时使用的 UUIDv7，避免暴露内部主键。 */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /** 与会话表一致的工作区隔离键，禁止通过消息表绕过租户校验。 */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 会话内部主键；客户端绝不接触此值。 */
    @Column(name = "session_id", nullable = false, updatable = false)
    private Long sessionId;

    /** 只允许 user 或 assistant，数据库约束会再次阻止脏角色写入。 */
    @Column(nullable = false, length = 20, updatable = false)
    private String role;

    /** 最终可见文本；不能写入模型思维链或未脱敏的工具调试信息。 */
    @Column(nullable = false, columnDefinition = "TEXT", updatable = false)
    private String content;

    /** 生成顺序使用带时区时间保存，前端历史按此字段升序渲染。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 仅供 JPA 使用，业务代码通过角色明确的工厂方法创建消息。 */
    protected ChatMessage() {
    }

    /** 创建用户输入消息。 */
    public static ChatMessage user(Long workspaceId, Long sessionId, String content) {
        return create(workspaceId, sessionId, "user", content);
    }

    /** 创建模型最终回答，调用方不得传入思维链或中间草稿。 */
    public static ChatMessage assistant(Long workspaceId, Long sessionId, String content) {
        return create(workspaceId, sessionId, "assistant", content);
    }

    /**
     * 统一初始化不可变归属字段，避免 user/assistant 两种写入路径出现工作区不一致。
     */
    private static ChatMessage create(Long workspaceId, Long sessionId, String role, String content) {
        ChatMessage message = new ChatMessage();
        message.publicId = UuidCreator.getTimeOrdered();
        message.workspaceId = workspaceId;
        message.sessionId = sessionId;
        message.role = role;
        message.content = content;
        message.createdAt = OffsetDateTime.now();
        return message;
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

    public Long getSessionId() {
        return sessionId;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
