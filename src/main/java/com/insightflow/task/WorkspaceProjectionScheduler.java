package com.insightflow.task;

import com.insightflow.entity.ImportFile;
import com.insightflow.repository.ImportFileRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.UUID;

/**
 * 自动投影的持久调度入口。
 *
 * <p>提交后的即时唤醒只降低等待时间；固定扫描才是应用重启、线程异常或进程中断后的恢复保证。</p>
 */
@Component
public class WorkspaceProjectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceProjectionScheduler.class);

    /** 租约服务负责 PostgreSQL 领取和文件/投影进入 running 的原子状态变更。 */
    private final WorkspaceProjectionLeaseService leaseService;

    /** Worker 在独立受限线程池内收敛投影状态。 */
    private final WorkspaceProjectionTaskRunner taskRunner;

    /** 投影命令服务，为 pending 文件创建投影任务。 */
    private final WorkspaceProjectionCommandService commandService;

    /** 文件仓储，扫描 pending 投影文件。 */
    private final ImportFileRepository importFileRepository;

    /** 每轮领取上限防止积压投影任务占满单体资源。 */
    private final int maxDispatchPerCycle;

    /** 应用实例级执行标识，租约过期后其它实例可安全接管。 */
    private final String workerId = "projector-" + UUID.randomUUID();

    /** 构造持久投影调度器。 */
    public WorkspaceProjectionScheduler(
            WorkspaceProjectionLeaseService leaseService,
            WorkspaceProjectionTaskRunner taskRunner,
            @Lazy WorkspaceProjectionCommandService commandService,
            ImportFileRepository importFileRepository,
            @Value("${insightflow.projection.max-dispatch-per-cycle}") int maxDispatchPerCycle) {
        this.leaseService = leaseService;
        this.taskRunner = taskRunner;
        this.commandService = commandService;
        this.importFileRepository = importFileRepository;
        this.maxDispatchPerCycle = maxDispatchPerCycle;
    }

    /** 固定延迟扫描 queued 或过期租约的 projection 任务。 */
    @Scheduled(fixedDelayString = "${insightflow.projection.dispatch-delay-ms}")
    public void scheduledDispatch() {
        enqueuePendingFiles();
        dispatchClaimableTasks();
    }

    /** 扫描 pending 投影文件，为每个文件创建投影任务。 */
    private void enqueuePendingFiles() {
        try {
            List<ImportFile> pendingFiles = importFileRepository
                    .findByProjectionStatusAndStatus("pending", "processed");
            for (ImportFile file : pendingFiles) {
                try {
                    commandService.enqueueForImportedFile(file.getWorkspaceId(), file.getId());
                } catch (Exception e) {
                    log.warn("为文件 {} 创建投影任务失败: {}", file.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("扫描 pending 投影文件失败: {}", e.getMessage());
        }
    }

    /** 新命令提交后和定时扫描均调用此方法；没有可领取任务时立即结束本轮。 */
    public void dispatchClaimableTasks() {
        for (int dispatched = 0; dispatched < maxDispatchPerCycle; dispatched++) {
            WorkspaceProjectionLeaseService.ClaimedTask claimed = leaseService.claimNext(workerId).orElse(null);
            if (claimed == null) {
                return;
            }
            taskRunner.run(claimed.taskId(), claimed.workerId(), claimed.executionVersion());
        }
    }
}
