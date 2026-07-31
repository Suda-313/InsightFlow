package com.insightflow.entity;

import com.github.f4b6a3.uuid.UuidCreator;
import com.insightflow.agent.investigation.ChatSessionFocus;
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

    /**
     * 当前会话正在讨论的主题名或短语；多轮指代改写时补全 query，空表示尚未建立焦点。
     */
    @Column(name = "focus_topic_key", length = 120)
    private String focusTopicKey;

    /** 调查时间窗的人类可读标签，如「近14天」；来自 Tool 证据 id 而非用户自由输入。 */
    @Column(name = "focus_time_window", length = 60)
    private String focusTimeWindow;

    /** 当前讨论涉及的版本号标签，如 1.4；来自证据或用户消息中的结构化 token。 */
    @Column(name = "focus_version_label", length = 60)
    private String focusVersionLabel;

    /** 焦点最后一次被确定性抽取更新的时间；仅审计用，不参与检索过滤。 */
    @Column(name = "focus_updated_at")
    private OffsetDateTime focusUpdatedAt;

    /**
     * 超出 {@link ConversationService#recentMessagesForModel} 窗口的更早对话确定性摘要；
     * 由 {@link SessionRollingSummaryBuilder} 维护，不存模型推理。
     */
    @Column(name = "rolling_summary", columnDefinition = "TEXT")
    private String rollingSummary;

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

    /**
     * 用本轮抽取到的焦点更新会话；空焦点或不完整槽位不得覆盖已有非空值。
     *
     * <p>避免一次无法识别主题的泛问把上一轮已确定的调查对象清空。</p>
     */
    public void updateFocus(ChatSessionFocus focus) {
        if (focus == null || focus.isEmpty()) {
            return;
        }
        if (focus.topicKey() != null && !focus.topicKey().isBlank()) {
            this.focusTopicKey = focus.topicKey().trim();
        }
        if (focus.timeWindow() != null && !focus.timeWindow().isBlank()) {
            this.focusTimeWindow = focus.timeWindow().trim();
        }
        if (focus.versionLabel() != null && !focus.versionLabel().isBlank()) {
            this.focusVersionLabel = focus.versionLabel().trim();
        }
        this.focusUpdatedAt = OffsetDateTime.now();
        touch();
    }

    /** 读取当前会话焦点；字段可能部分为空。 */
    public ChatSessionFocus currentFocus() {
        return ChatSessionFocus.of(focusTopicKey, focusTimeWindow, focusVersionLabel);
    }

    public String getFocusTopicKey() {
        return focusTopicKey;
    }

    public String getFocusTimeWindow() {
        return focusTimeWindow;
    }

    public String getFocusVersionLabel() {
        return focusVersionLabel;
    }

    public OffsetDateTime getFocusUpdatedAt() {
        return focusUpdatedAt;
    }

    /** 读取持久化的滚动摘要；无更早轮次或未超窗口时为 null。 */
    public String getRollingSummary() {
        return rollingSummary;
    }

    /** 更新滚动摘要；null 或空白表示清除（例如消息数回落到窗口内时）。 */
    public void updateRollingSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            this.rollingSummary = null;
        } else {
            this.rollingSummary = summary.trim();
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
