package com.insightflow.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.insightflow.agent.investigation.InvestigationEvidence;
import com.insightflow.entity.ChatMessage;
import com.insightflow.entity.ChatSession;
import com.insightflow.config.AgentApiKeyPresentCondition;
import com.insightflow.service.ChatService;
import com.insightflow.service.ConversationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Conditional;

/**
 * 对话 HTTP 边界：管理会话恢复、历史读取和消息发送。
 *
 * <p>路径和响应只携带工作区、会话、消息的 public_id；内部 workspace_id 和数据库主键仅在服务层使用。
 * 所有会话归属判断统一委托 ConversationService，Controller 不自行访问仓储。</p>
 */
@RestController
@Conditional(AgentApiKeyPresentCondition.class)
@RequestMapping("/api/v1/workspaces/{workspaceId}/chat")
public class ChatController {

    /** 生成答案并保存最终消息的 Agent 用例。 */
    private final ChatService chatService;

    /** 会话与消息的持久化用例，负责工作区隔离。 */
    private final ConversationService conversationService;

    /** 构造器显式区分模型生成职责与会话生命周期职责。 */
    public ChatController(ChatService chatService, ConversationService conversationService) {
        this.chatService = chatService;
        this.conversationService = conversationService;
    }

    /** 创建空会话，前端新建对话后才能发送第一条消息。 */
    @PostMapping("/sessions")
    public SessionResponse createSession(@PathVariable UUID workspaceId) {
        return SessionResponse.from(conversationService.createSession(workspaceId));
    }

    /** 返回未归档会话，前端刷新后选择 updated_at 最新的一条进行恢复。 */
    @GetMapping("/sessions")
    public List<SessionResponse> listSessions(@PathVariable UUID workspaceId) {
        return conversationService.listActiveSessions(workspaceId).stream()
                .map(SessionResponse::from)
                .toList();
    }

    /** 读取一个已验证归属的会话历史，供页面恢复展示。 */
    @GetMapping("/sessions/{sessionId}/messages")
    public List<MessageResponse> listMessages(
            @PathVariable UUID workspaceId,
            @PathVariable UUID sessionId) {
        return conversationService.listMessages(workspaceId, sessionId).stream()
                .map(MessageResponse::from)
                .toList();
    }

    /** 归档会话替代物理删除；页面可随后创建新会话。 */
    @DeleteMapping("/sessions/{sessionId}")
    public void archiveSession(@PathVariable UUID workspaceId, @PathVariable UUID sessionId) {
        conversationService.archiveSession(workspaceId, sessionId);
    }

    /**
     * 提交一条用户问题并返回模型最终回答。
     *
     * <p>当前模型调用原本就是单次完整响应，并非真正 token 流，因此返回 JSON 而不是伪 SSE；以后接入真实
     * 流式模型时可新增专门端点，不能改变已持久化消息的语义。</p>
     */
    @PostMapping
    public ChatResponse chat(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody ChatRequest request) {
        ChatService.ChatReply reply = chatService.chat(workspaceId, request.sessionId(), request.message());
        return new ChatResponse(reply.sessionId(), reply.traceId(), reply.content(), reply.evidence());
    }

    /** 发送消息的最小契约：会话必须显式存在，避免服务端悄然创建无法恢复的临时会话。 */
    public record ChatRequest(
            @NotNull @JsonProperty("session_id") UUID sessionId,
            @NotBlank @JsonProperty("message") String message) {
    }

    /** 会话列表响应；只公开恢复页面所需字段。 */
    public record SessionResponse(
            UUID id,
            String title,
            @JsonProperty("created_at") OffsetDateTime createdAt,
            @JsonProperty("updated_at") OffsetDateTime updatedAt) {

        static SessionResponse from(ChatSession session) {
            return new SessionResponse(
                    session.getPublicId(), session.getTitle(), session.getCreatedAt(), session.getUpdatedAt());
        }
    }

    /** 历史消息响应；不含内部 session_id、workspace_id、模型元数据或思维链。 */
    public record MessageResponse(
            UUID id,
            String role,
            String content,
            @JsonProperty("created_at") OffsetDateTime createdAt) {

        static MessageResponse from(ChatMessage message) {
            return new MessageResponse(
                    message.getPublicId(), message.getRole(), message.getContent(), message.getCreatedAt());
        }
    }

    /** 发送消息响应同时提供会话与 AgentRun Trace，便于前端或支持人员关联一次模型调用。 */
    public record ChatResponse(
            @JsonProperty("session_id") UUID sessionId,
            @JsonProperty("trace_id") UUID traceId,
            String content,
            List<InvestigationEvidence> evidence) {

        /** 仅返回本次运行的受控证据索引，防止调用方修改服务层集合。 */
        public ChatResponse {
            evidence = List.copyOf(evidence);
        }
    }
}
