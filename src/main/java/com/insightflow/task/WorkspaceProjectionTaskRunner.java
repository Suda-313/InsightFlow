package com.insightflow.task;

import com.insightflow.entity.AsyncTask;
import com.insightflow.repository.AsyncTaskRepository;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 自动投影 Worker 的单体内执行入口。
 *
 * <p>当前迭代只验证可靠的领取和状态收敛。主题归并、趋势、EWMA 与 Alert 会在此入口内按既有
 * WorkspaceProjection 事务边界逐步加入，而不会反向耦合到 CSV 导入线程。</p>
 */
@Component
public class WorkspaceProjectionTaskRunner {

    /** 任务仓储让 Worker 在异步线程开始时再次核验持有的租约。 */
    private final AsyncTaskRepository taskRepository;

    /** 完成服务在独立事务中收敛最终状态，避免未来计算异常回滚失败标记。 */
    private final WorkspaceProjectionCompletionService completionService;

    /** 构造投影 Worker。 */
    public WorkspaceProjectionTaskRunner(
            AsyncTaskRepository taskRepository, WorkspaceProjectionCompletionService completionService) {
        this.taskRepository = taskRepository;
        this.completionService = completionService;
    }

    /**
     * 在线程池执行状态投影；重复调度或租约已转移时安全返回，绝不再次推进文件状态。
     */
    @Async("projectionTaskExecutor")
    public void run(UUID taskPublicId, String workerId) {
        AsyncTask task = taskRepository.findByPublicId(taskPublicId).orElse(null);
        if (task == null || !"projection".equals(task.getTaskType()) || !task.isLeaseOwnedBy(workerId)) {
            return;
        }
        try {
            completionService.complete(taskPublicId, workerId);
        } catch (Exception exception) {
            completionService.fail(taskPublicId, workerId, "PROJECTION_EXECUTION_FAILED", "看板投影执行失败，请稍后重试。");
        }
    }
}
