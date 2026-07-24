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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 一次模型调用的可审计运行记录。
 *
 * <p>内部 {@code id} 只服务于数据库关联；外部 API 和日志关联均使用 UUIDv7 {@code publicId} 作为 Trace 标识。
 * {@code workspaceId} 是运行记录的强制隔离键，任何读取或状态更新都必须同时带上该字段。</p>
 */
@Entity
@Table(name = "agent_run")
public class AgentRun {

    /** 数据库内部主键，不能经由 HTTP 或日志暴露。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对外可见的运行 Trace 标识，替代连续内部行号。 */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /** 所有 AgentRun 查询与写入都必须参与的工作区隔离键。 */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 调用来源，例如 chat；后续分析 Agent 复用该字段而不新建审计表。 */
    @Column(name = "agent_type", nullable = false, length = 50, updatable = false)
    private String agentType;

    /** running / succeeded / failed，体现一次模型调用的有限生命周期。 */
    @Column(nullable = false, length = 20)
    private String status;

    /** Prompt 模板版本，不保存完整系统 Prompt，以便后续评测作版本比较。 */
    @Column(name = "prompt_version", nullable = false, length = 100, updatable = false)
    private String promptVersion;

    /** 实际请求的模型名，用于比较不同模型的质量、延迟和成本。 */
    @Column(name = "model_name", nullable = false, length = 120, updatable = false)
    private String modelName;

    /** 检索策略版本；聊天首版没有 RAG，固定记录为 none。 */
    @Column(name = "retrieval_version", nullable = false, length = 100, updatable = false)
    private String retrievalVersion;

    /** 经脱敏与截断的输入摘要，不作为完整会话或原始 Prompt 的副本。 */
    @Column(name = "input_summary", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String inputSummary;

    /** 模型最终可见回答；禁止写入 reasoning_content 或中间思维草稿。 */
    @Column(name = "output_text", columnDefinition = "TEXT")
    private String outputText;

    /** 可选的结构化证据快照；聊天首版未调用 Tool 时保持 null。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_json", columnDefinition = "jsonb")
    private String evidenceJson;

    /** 模型返回的输入 Token；服务商未返回 Usage 时保留 null 而非猜测。 */
    @Column(name = "prompt_tokens")
    private Long promptTokens;

    /** 模型返回的生成 Token；字段名避免与 JPA Generation 术语混淆。 */
    @Column(name = "completion_tokens")
    private Long completionTokens;

    /** 模型返回的总 Token，供成本统计直接聚合。 */
    @Column(name = "total_tokens")
    private Long totalTokens;

    /** 从模型调用开始到成功或失败的服务端耗时，不包含前端网络时间。 */
    @Column(name = "latency_ms")
    private Long latencyMs;

    /** 固定业务错误码；异常正文和堆栈仅进入服务端日志。 */
    @Column(name = "error_code", length = 80)
    private String errorCode;

    /** 运行创建时间，代表模型调用开始前审计记录已落库。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 运行最终态时间；running 状态下保持 null。 */
    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    /** 仅供 JPA 反射使用；业务代码通过 {@link #start} 建立一致的运行初态。 */
    protected AgentRun() {
    }

    /**
     * 创建 running 状态的运行记录。
     */
    public static AgentRun start(
            Long workspaceId,
            String agentType,
            String promptVersion,
            String modelName,
            String retrievalVersion,
            String inputSummary) {
        AgentRun run = new AgentRun();
        run.publicId = UuidCreator.getTimeOrdered();
        run.workspaceId = workspaceId;
        run.agentType = agentType;
        run.status = "running";
        run.promptVersion = promptVersion;
        run.modelName = modelName;
        run.retrievalVersion = retrievalVersion;
        run.inputSummary = inputSummary;
        run.createdAt = OffsetDateTime.now();
        return run;
    }

    /**
     * 写入模型最终答案和精确 Usage；只有 running 记录可以进入成功终态。
     */
    public void succeed(
            String outputText,
            String evidenceJson,
            Long promptTokens,
            Long completionTokens,
            Long totalTokens,
            long latencyMs) {
        this.status = "succeeded";
        this.outputText = outputText;
        this.evidenceJson = evidenceJson;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.latencyMs = latencyMs;
        this.errorCode = null;
        this.completedAt = OffsetDateTime.now();
    }

    /**
     * 写入受控失败码，不将服务商异常正文、Prompt 或堆栈复制到审计表。
     */
    public void fail(String errorCode, long latencyMs) {
        this.status = "failed";
        this.errorCode = errorCode;
        this.latencyMs = latencyMs;
        this.completedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public Long getWorkspaceId() { return workspaceId; }
    public String getAgentType() { return agentType; }
    public String getStatus() { return status; }
    public String getPromptVersion() { return promptVersion; }
    public String getModelName() { return modelName; }
    public String getRetrievalVersion() { return retrievalVersion; }
    public String getInputSummary() { return inputSummary; }
    public String getOutputText() { return outputText; }
    public String getEvidenceJson() { return evidenceJson; }
    public Long getPromptTokens() { return promptTokens; }
    public Long getCompletionTokens() { return completionTokens; }
    public Long getTotalTokens() { return totalTokens; }
    public Long getLatencyMs() { return latencyMs; }
    public String getErrorCode() { return errorCode; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
}
