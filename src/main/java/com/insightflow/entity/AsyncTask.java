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
 * V1 异步命令的持久化状态机，当前承载 CSV 导入任务。
 *
 * <p>任务使用内部 id 关联反馈事件，使用 UUIDv7 对外返回。{@code workspaceId} 是任务和文件、
 * 结果查询之间的隔离锚点，调用方不能仅凭 task UUID 跨 Workspace 获取状态。</p>
 */
@Entity
@Table(name = "async_task")
public class AsyncTask {

    /**
     * 数据库内部任务键，用于导入反馈记录关联，禁止对外序列化。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 任务 API 和未来 SSE 使用的 UUIDv7。
     */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /**
     * 任务的一级隔离字段，必须与 payload 中的 import file 所属 Workspace 相同。
     */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /**
     * 当前导入任务明确关联的文件内部键，避免仅靠 JSON payload 反查文件结果。
     */
    @Column(name = "import_file_id", updatable = false)
    private Long importFileId;

    /**
     * 受控任务类型；本迭代只创建 import，后续分析和 Agent 复用该模型。
     */
    @Column(name = "task_type", nullable = false, length = 40, updatable = false)
    private String taskType;

    /**
     * queued / running / succeeded / partial_failed / failed 等可观察状态。
     */
    @Column(nullable = false, length = 30)
    private String status;

    /**
     * 客户端命令幂等键；数据库唯一约束阻止重试请求创建第二个导入任务。
     */
    @Column(name = "idempotency_key", nullable = false, length = 200, updatable = false)
    private String idempotencyKey;

    /**
     * 已校验的最小输入摘要，只保存 import file UUID，不保存原始 CSV 内容。
     */
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb", updatable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String payloadJson;

    /**
     * 脱敏后的导入计数与有限错误摘要，供文件结果页和任务状态页读取。
     */
    @Column(name = "result_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String resultJson;

    /**
     * 本次已领取次数；V1 的内存异步执行器不自动重试，但保留字段为后续恢复任务准备。
     */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    /**
     * 最大尝试次数由任务表事实记录，后续 Worker 领取逻辑不能静默无限重试。
     */
    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    /**
     * 面向用户的受控错误码，而不是异常类名或存储 SDK 错误细节。
     */
    @Column(name = "error_code", length = 80)
    private String errorCode;

    /**
     * 有限长度、无 PII 的错误摘要；详细堆栈只进入服务器日志。
     */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /**
     * 创建时间表示命令被受理的时刻，不表示导入已经完成。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * 每次状态、进度摘要或失败原因变化后更新，供轮询使用。
     */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * 当前执行实例的随机标识；只有持有相同租约的 Worker 可以结束任务。
     */
    @Column(name = "lease_owner", length = 100)
    private String leaseOwner;

    /**
     * 租约截止时间；进程崩溃后调度器把过期 running 任务视为可重新领取。
     */
    @Column(name = "lease_expires_at")
    private OffsetDateTime leaseExpiresAt;

    /**
     * 最近一次领取开始时间，用于观测实际执行而非仅查看命令受理时间。
     */
    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    /**
     * 进入成功、部分失败或失败终态的时刻；未结束任务保持 null。
     */
    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    /**
     * JPA 反射构造器；业务代码必须调用 {@link #queuedImport(Long, String, String)}。
     */
    protected AsyncTask() {
    }

    /**
     * 创建一个尚未被 Worker 领取的导入任务；payload 只含已公开的文件 UUID。
     */
    public static AsyncTask queuedImport(
            Long workspaceId, Long importFileId, String idempotencyKey, String payloadJson) {
        AsyncTask task = queuedWorkspaceTask(workspaceId, idempotencyKey, payloadJson, "import");
        task.importFileId = importFileId;
        return task;
    }

    /**
     * 创建自动看板投影任务；来源文件列表保存在 payload，而不是伪装成单一 import_file 关联。
     */
    public static AsyncTask queuedProjection(Long workspaceId, String idempotencyKey, String payloadJson) {
        return queuedWorkspaceTask(workspaceId, idempotencyKey, payloadJson, "projection");
    }

    /**
     * 创建只读报告任务；报告任务复用租约和重试机制，但不拥有任何导入文件状态。
     */
    public static AsyncTask queuedReport(Long workspaceId, String idempotencyKey, String payloadJson) {
        return queuedWorkspaceTask(workspaceId, idempotencyKey, payloadJson, "report");
    }

    /**
     * 统一初始化 Workspace 级异步命令，保证三类任务使用相同的公开 ID、重试上限与初始状态。
     */
    private static AsyncTask queuedWorkspaceTask(
            Long workspaceId, String idempotencyKey, String payloadJson, String taskType) {
        AsyncTask task = new AsyncTask();
        task.publicId = UuidCreator.getTimeOrdered();
        task.workspaceId = workspaceId;
        task.taskType = taskType;
        task.status = "queued";
        task.idempotencyKey = idempotencyKey;
        task.payloadJson = payloadJson;
        task.attemptCount = 0;
        task.maxAttempts = 3;
        task.createdAt = OffsetDateTime.now();
        task.updatedAt = task.createdAt;
        return task;
    }

    /**
     * 原子业务含义上的领取：状态从 queued 进入 running，尝试次数只在真正执行前递增。
     */
    public void markRunning() {
        this.status = "running";
        this.attemptCount++;
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * 以短租约原子领取任务；同一时刻只能由数据库锁选中的一个 Worker 写入 owner。
     */
    public void claim(String workerId, OffsetDateTime leaseUntil) {
        this.status = "running";
        this.leaseOwner = workerId;
        this.leaseExpiresAt = leaseUntil;
        this.attemptCount++;
        this.startedAt = OffsetDateTime.now();
        this.updatedAt = this.startedAt;
    }

    /**
     * queued 任务可立即领取；running 任务只有租约缺失或已过期后才允许恢复执行。
     */
    public boolean canBeClaimedAt(OffsetDateTime now) {
        return "queued".equals(status)
                || ("running".equals(status) && (leaseExpiresAt == null || !leaseExpiresAt.isAfter(now)));
    }

    /**
     * 最大尝试次数是任务事实而非配置猜测，恢复器必须在领取前尊重该上限。
     */
    public boolean hasAttemptsRemaining() {
        return attemptCount < maxAttempts;
    }

    /**
     * 结束任务前再次确认租约，过期 Worker 不得覆盖新 Worker 的最终结果。
     */
    public boolean isLeaseOwnedBy(String workerId) {
        return "running".equals(status) && workerId != null && workerId.equals(leaseOwner);
    }

    /**
     * 全部有效行完成且无行级错误时写入成功摘要。
     */
    public void markSucceeded(String resultJson) {
        this.status = "succeeded";
        this.resultJson = resultJson;
        this.errorCode = null;
        this.errorMessage = null;
        finish();
    }

    /**
     * 允许坏行不阻断其它有效反馈，但明确向用户暴露 partial_failed 而非伪装成功。
     */
    public void markPartialFailed(String resultJson) {
        this.status = "partial_failed";
        this.resultJson = resultJson;
        finish();
    }

    /**
     * 对文件读取、映射解析等任务级错误收敛为受控失败状态和安全摘要。
     */
    public void markFailed(String code, String message) {
        this.status = "failed";
        this.errorCode = code;
        this.errorMessage = message;
        finish();
    }

    /**
     * 所有终态统一释放租约并写入完成时间，确保恢复扫描不会重新领取已结束的任务。
     */
    private void finish() {
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
        this.finishedAt = OffsetDateTime.now();
        this.updatedAt = this.finishedAt;
    }

    /**
     * 返回内部任务键，仅供反馈事件和持久化层关联。
     */
    public Long getId() {
        return id;
    }

    /**
     * 返回 API / 轮询使用的公开任务 UUID。
     */
    public UUID getPublicId() {
        return publicId;
    }

    /**
     * 返回任务所属 Workspace 内部键，所有任务读取必须用它二次过滤。
     */
    public Long getWorkspaceId() {
        return workspaceId;
    }

    /**
     * 返回关联文件内部键，仅供导入结果读取和 Worker 执行使用。
     */
    public Long getImportFileId() {
        return importFileId;
    }

    /**
     * 返回受控任务类型，前端只能据此展示对应状态含义。
     */
    public String getTaskType() {
        return taskType;
    }

    /**
     * 返回当前任务状态；202 只意味着初始 queued，不意味着该字段已完成。
     */
    public String getStatus() {
        return status;
    }

    /**
     * 返回幂等键用于检测同键不同 payload 的冲突，普通 API 响应不暴露该值。
     */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /**
     * 返回安全的输入摘要，异步 Worker 用它关联待处理文件。
     */
    public String getPayloadJson() {
        return payloadJson;
    }

    /**
     * 返回任务完成后的计数和有限错误摘要，尚未完成时为 null。
     */
    public String getResultJson() {
        return resultJson;
    }

    /**
     * 返回尝试次数，便于未来任务恢复和观测。
     */
    public int getAttemptCount() {
        return attemptCount;
    }

    /**
     * 返回该任务允许的最大领取次数，供调度器在重新投递前判断是否应永久失败。
     */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * 返回当前持有者，仅用于 Worker 完成时的租约校验，不对 API 暴露。
     */
    public String getLeaseOwner() {
        return leaseOwner;
    }

    /**
     * 返回用户可处理的错误码，成功任务为 null。
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * 返回脱敏、限长错误摘要，详细异常不从该字段泄漏。
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * 返回受理时间，供列表和审计展示。
     */
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * 返回状态最后变更时间，供客户端轮询判断。
     */
    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
