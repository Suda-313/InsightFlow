package com.insightflow.investigation;

import com.insightflow.entity.Alert;
import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.InvestigationCase;
import com.insightflow.entity.InvestigationEvidenceSnapshot;
import com.insightflow.repository.AlertRepository;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.InvestigationCaseRepository;
import com.insightflow.repository.InvestigationEvidenceSnapshotRepository;
import com.insightflow.task.TaskLeaseHeartbeat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 告警调查异步 Worker：只读 Tool 取证、冻结快照并转入人工复核。
 *
 * <p>Worker 不调用任何处置服务，也不修改 Alert；它只在持有有效租约时写入调查卡片和证据快照。所有可变处置必须由后续 ProposalCommandService 经人工确认执行。</p>
 */
@Component
public class InvestigationTaskRunner {

    /** 调查异常日志不含原始反馈、密码或模型推理，只用于服务端排障。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(InvestigationTaskRunner.class);

    /** Worker 必须重新读取任务以校验租约，不能信任调度器传入的对象。 */
    private final AsyncTaskRepository asyncTaskRepository;

    /** 调查卡片以任务与 Workspace 双键读取，防止串任务写入。 */
    private final InvestigationCaseRepository investigationCaseRepository;

    /** 告警是不可变触发事实，Worker 只读它来组装快照。 */
    private final AlertRepository alertRepository;

    /** 快照仓储提供重试幂等守卫。 */
    private final InvestigationEvidenceSnapshotRepository evidenceSnapshotRepository;

    /** 固定 Tool 计划和脱敏边界集中在装配器中。 */
    private final InvestigationEvidenceAssembler evidenceAssembler;

    /** 终态更新在独立事务处理，确保异常不会留下永久 running。 */
    private final InvestigationTaskCompletionService completionService;
    /** Investigation stays read-only, while its task ownership is renewed by this separate component. */
    private final TaskLeaseHeartbeat leaseHeartbeat;
    private final java.time.Duration maxRuntime;

    /** 构造器显式声明 Worker 的只读数据来源与受控写入出口。 */
    public InvestigationTaskRunner(
            AsyncTaskRepository asyncTaskRepository,
            InvestigationCaseRepository investigationCaseRepository,
            AlertRepository alertRepository,
            InvestigationEvidenceSnapshotRepository evidenceSnapshotRepository,
            InvestigationEvidenceAssembler evidenceAssembler,
            InvestigationTaskCompletionService completionService,
            TaskLeaseHeartbeat leaseHeartbeat,
            @org.springframework.beans.factory.annotation.Value("${insightflow.task.investigation-max-runtime-seconds:600}") long maxRuntimeSeconds) {
        this.asyncTaskRepository = asyncTaskRepository;
        this.investigationCaseRepository = investigationCaseRepository;
        this.alertRepository = alertRepository;
        this.evidenceSnapshotRepository = evidenceSnapshotRepository;
        this.evidenceAssembler = evidenceAssembler;
        this.completionService = completionService;
        this.leaseHeartbeat = leaseHeartbeat;
        this.maxRuntime = java.time.Duration.ofSeconds(maxRuntimeSeconds);
    }

    /**
     * 在独立线程池执行调查；重复调度、缺失任务或租约已转移时安全返回，不产生额外写入。
     */
    @Async("investigationTaskExecutor")
    public void run(UUID taskPublicId, String workerId) { run(taskPublicId, workerId, -1); }

    /** The frozen evidence is written only while the claim version remains current. */
    public void run(UUID taskPublicId, String workerId, int executionVersion) {
        AsyncTask task = asyncTaskRepository.findByPublicId(taskPublicId).orElse(null);
        int version = executionVersion < 0 ? task == null ? -1 : task.getAttemptCount() : executionVersion;
        if (task == null || !"investigation".equals(task.getTaskType()) || !task.isLeaseOwnedBy(workerId, version)) {
            return;
        }
        TaskLeaseHeartbeat.Guard guard = leaseHeartbeat.register(taskPublicId, workerId, version, maxRuntime);
        try {
            guard.ensureActive();
            InvestigationCase investigation = investigationCaseRepository
                    .findByAsyncTaskIdAndWorkspaceId(task.getId(), task.getWorkspaceId())
                    .orElse(null);
            if (investigation == null) {
                completionService.fail(taskPublicId, workerId, version, "INVESTIGATION_CASE_NOT_FOUND", "调查卡片不存在或不属于当前工作区");
                return;
            }
            investigation.markInvestigating();
            investigationCaseRepository.saveAndFlush(investigation);
            if (evidenceSnapshotRepository.existsByInvestigationCaseIdAndWorkspaceId(
                    investigation.getId(), investigation.getWorkspaceId())) {
                completionService.complete(taskPublicId, workerId, version, evidenceSnapshotRepository
                        .findByInvestigationCaseIdAndWorkspaceIdOrderByCreatedAtAsc(investigation.getId(), investigation.getWorkspaceId()).size());
                return;
            }
            Alert alert = alertRepository.findById(investigation.getAlertId())
                    .filter(found -> investigation.getWorkspaceId().equals(found.getWorkspaceId()))
                    .orElse(null);
            if (alert == null) {
                completionService.fail(taskPublicId, workerId, version, "ALERT_NOT_FOUND", "调查触发告警不存在或不属于当前工作区");
                return;
            }
            List<InvestigationEvidenceSnapshot> snapshots = evidenceAssembler.assemble(investigation, alert);
            guard.ensureActive();
            evidenceSnapshotRepository.saveAll(snapshots);
            guard.ensureActive();
            completionService.complete(taskPublicId, workerId, version, snapshots.size());
        } catch (TaskLeaseHeartbeat.TaskExecutionTimeoutException timeout) {
            completionService.fail(taskPublicId, workerId, version, "TASK_EXECUTION_TIMEOUT", "调查任务超过最大执行时长。");
        } catch (TaskLeaseHeartbeat.TaskLeaseLostException lost) {
            return;
        } catch (Exception exception) {
            LOGGER.error("调查任务执行失败, task_id={}", taskPublicId, exception);
            completionService.fail(taskPublicId, workerId, version, "INVESTIGATION_FAILED", "调查证据装配失败，请稍后重试");
        } finally {
            leaseHeartbeat.unregister(taskPublicId, version);
        }
    }
}
