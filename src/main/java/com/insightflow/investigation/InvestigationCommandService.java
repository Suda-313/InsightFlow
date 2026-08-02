package com.insightflow.investigation;

import com.insightflow.entity.Alert;
import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.InvestigationCase;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.AlertRepository;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.InvestigationCaseRepository;
import com.insightflow.security.WorkspaceAccessService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 告警调查任务的受控创建入口。
 *
 * <p>人工重试路径先校验 Workspace 读权限；系统告警事件路径只接收已持久化的内部键。两条路径最终复用同一幂等创建方法，保证一个 Alert 最多对应一个调查卡片和一个 investigation 异步任务。</p>
 */
@Service
public class InvestigationCommandService {

    /** 人工 API 路径的基础 Workspace 访问校验。 */
    private final WorkspaceAccessService accessService;

    /** 告警读取始终按 workspace_id 和公开 UUID 双重过滤。 */
    private final AlertRepository alertRepository;

    /** 调查卡片唯一约束是业务幂等的持久化兜底。 */
    private final InvestigationCaseRepository investigationCaseRepository;

    /** 复用已有异步任务表、租约和重试机制，不引入额外队列。 */
    private final AsyncTaskRepository asyncTaskRepository;

    /** 构造器仅注入授权与持久化边界，任务具体执行由 Worker 负责。 */
    public InvestigationCommandService(
            WorkspaceAccessService accessService,
            AlertRepository alertRepository,
            InvestigationCaseRepository investigationCaseRepository,
            AsyncTaskRepository asyncTaskRepository) {
        this.accessService = accessService;
        this.alertRepository = alertRepository;
        this.investigationCaseRepository = investigationCaseRepository;
        this.asyncTaskRepository = asyncTaskRepository;
    }

    /**
     * 用户触发的调查重试入口，只允许读取自己有范围权限的告警。
     */
    @Transactional
    public InvestigationCase enqueue(UUID workspacePublicId, UUID alertPublicId) {
        Workspace workspace = accessService.requireRead(workspacePublicId);
        Alert alert = alertRepository.findByWorkspaceIdAndPublicId(workspace.getId(), alertPublicId)
                .orElseThrow(() -> new IllegalArgumentException("告警不存在或不属于当前工作区"));
        return enqueueForAlert(alert);
    }

    /**
     * 告警事务提交后的内部事件入口；事件仅在提交后处理，避免回滚告警仍创建孤立调查。
     * 监听器本身须开启新事务：AFTER_COMMIT 阶段已无投影事务，且同类自调用不会触发 enqueueForAlert 的 @Transactional。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAlertCreated(AlertCreatedEvent event) {
        alertRepository.findById(event.alertId())
                .filter(alert -> event.workspaceId().equals(alert.getWorkspaceId()))
                .ifPresent(this::enqueueForAlert);
    }

    /**
     * 为已持久化告警创建调查卡片和任务；重复调用直接复用既有卡片。
     */
    @Transactional
    public InvestigationCase enqueueForAlert(Alert alert) {
        Optional<InvestigationCase> existing = investigationCaseRepository
                .findByWorkspaceIdAndAlertId(alert.getWorkspaceId(), alert.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        String idempotencyKey = "investigation:" + alert.getPublicId();
        Optional<AsyncTask> existingTask = asyncTaskRepository.findByWorkspaceIdAndTaskTypeAndIdempotencyKey(
                alert.getWorkspaceId(), "investigation", idempotencyKey);
        if (existingTask.isPresent()) {
            return investigationCaseRepository.findByAsyncTaskIdAndWorkspaceId(existingTask.get().getId(), alert.getWorkspaceId())
                    .orElseThrow(() -> new IllegalStateException("调查任务缺少对应卡片"));
        }
        InvestigationCase investigation = investigationCaseRepository.save(InvestigationCase.queued(alert.getWorkspaceId(), alert.getId()));
        // IDENTITY 主键须 flush 后才可用；save 未 flush 时 getId() 为 null，attachTask 会拒绝绑定。
        AsyncTask task = asyncTaskRepository.saveAndFlush(AsyncTask.queuedInvestigation(
                alert.getWorkspaceId(), idempotencyKey, "{\"alert_id\":\"" + alert.getPublicId() + "\"}"));
        investigation.attachTask(task.getId());
        return investigationCaseRepository.save(investigation);
    }
}
