package com.insightflow.task;

import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.WorkspaceProjection;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.WorkspaceProjectionRepository;
import com.insightflow.service.analysis.WorkspaceProjectionExecutionService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 自动投影 Worker 单体内执行入口：先由执行服务写主题事实，再由完成服务收敛终态。
 * 执行失败只标 projection_failed，不回滚已成功的 CSV 导入。
 */
@Component
public class WorkspaceProjectionTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceProjectionTaskRunner.class);

    /** 任务仓储让 Worker 在异步线程开始时再次核验持有的租约。 */
    private final AsyncTaskRepository taskRepository;
    /** 投影记录仓储，定位本次投影 id 与 workspace。 */
    private final WorkspaceProjectionRepository projectionRepository;
    /** 执行服务在单事务内写主题事实与 source window。 */
    private final WorkspaceProjectionExecutionService executionService;
    /** 完成服务在独立短事务中收敛最终状态。 */
    private final WorkspaceProjectionCompletionService completionService;

    /** 半完成投影判定；禁止仅有 L1/data_cell 无 L2 时标记 succeeded。 */
    private final ProjectionRequeueSupport requeueSupport;

    /** 构造投影 Worker。 */
    public WorkspaceProjectionTaskRunner(AsyncTaskRepository taskRepository,
                                         WorkspaceProjectionRepository projectionRepository,
                                         WorkspaceProjectionExecutionService executionService,
                                         WorkspaceProjectionCompletionService completionService,
                                         ProjectionRequeueSupport requeueSupport) {
        this.taskRepository = taskRepository;
        this.projectionRepository = projectionRepository;
        this.executionService = executionService;
        this.completionService = completionService;
        this.requeueSupport = requeueSupport;
    }

    /** 在线程池执行状态投影；重复调度或租约已转移时安全返回。 */
    @Async("projectionTaskExecutor")
    public void run(UUID taskPublicId, String workerId) {
        AsyncTask task = taskRepository.findByPublicId(taskPublicId).orElse(null);
        if (task == null || !"projection".equals(task.getTaskType()) || !task.isLeaseOwnedBy(workerId)) {
            return;
        }
        try {
            WorkspaceProjection projection = projectionRepository
                    .findByAsyncTaskIdAndWorkspaceId(task.getId(), task.getWorkspaceId())
                    .orElse(null);
            if (projection == null) {
                completionService.fail(taskPublicId, workerId, "PROJECTION_RECORD_NOT_FOUND", "投影状态记录不存在。");
                return;
            }
            boolean hasEvents = executionService.execute(projection.getId(), task.getWorkspaceId());
            if (!hasEvents) {
                completionService.fail(taskPublicId, workerId, "PROJECTION_SOURCE_EMPTY", "投影来源事件为空。");
                return;
            }
            if (!requeueSupport.isProjectionFactsComplete(task.getWorkspaceId(), projection.getId())) {
                log.error(
                        "投影 {} 工作区 {} 缺少 L2 标注（疑似旧字节码 Worker）；清理半完成事实并标记失败",
                        projection.getId(),
                        task.getWorkspaceId());
                requeueSupport.wipeAnalysisFacts(task.getWorkspaceId());
                completionService.fail(
                        taskPublicId,
                        workerId,
                        "PROJECTION_INCOMPLETE",
                        "投影未写入 L2 表达标注。请执行 mvn clean compile 后完全重启后端再试。");
                return;
            }
            completionService.complete(taskPublicId, workerId);
        } catch (Exception exception) {
            completionService.fail(taskPublicId, workerId, "PROJECTION_EXECUTION_FAILED", "看板投影执行失败，请稍后重试。");
        }
    }
}
