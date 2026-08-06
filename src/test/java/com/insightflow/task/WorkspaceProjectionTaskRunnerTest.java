package com.insightflow.task;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.WorkspaceProjection;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.WorkspaceProjectionRepository;
import com.insightflow.service.analysis.WorkspaceProjectionExecutionService;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 验证投影 Worker 仅在自己持有有效租约时才允许进入完成服务。
 */
class WorkspaceProjectionTaskRunnerTest {

    /**
     * 有效领取的 projection 任务应被交给执行服务与完成服务，而不在 Runner 内直接写最终状态。
     */
    @Test
    void delegatesClaimedProjectionToCompletionService() {
        AsyncTaskRepository taskRepository = mock(AsyncTaskRepository.class);
        WorkspaceProjectionRepository projectionRepository = mock(WorkspaceProjectionRepository.class);
        WorkspaceProjectionExecutionService executionService = mock(WorkspaceProjectionExecutionService.class);
        WorkspaceProjectionCompletionService completionService = mock(WorkspaceProjectionCompletionService.class);
        ProjectionRequeueSupport requeueSupport = mock(ProjectionRequeueSupport.class);
        TaskLeaseHeartbeat leaseHeartbeat = mock(TaskLeaseHeartbeat.class);
        TaskLeaseHeartbeat.Guard guard = mock(TaskLeaseHeartbeat.Guard.class);

        AsyncTask task = AsyncTask.queuedProjection(7L, "projection:file:11:rules:v1", "{}");
        task.claim("projection-worker", OffsetDateTime.now().plusMinutes(1));
        when(taskRepository.findByPublicId(task.getPublicId())).thenReturn(Optional.of(task));

        WorkspaceProjection projection = WorkspaceProjection.queued(7L, task.getId(), "rules:v1");
        when(projectionRepository.findByAsyncTaskIdAndWorkspaceId(task.getId(), task.getWorkspaceId()))
                .thenReturn(Optional.of(projection));
        when(executionService.execute(projection.getId(), task.getWorkspaceId())).thenReturn(true);
        when(requeueSupport.isProjectionFactsComplete(task.getWorkspaceId(), projection.getId())).thenReturn(true);
        when(leaseHeartbeat.register(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(guard);

        WorkspaceProjectionTaskRunner runner = new WorkspaceProjectionTaskRunner(
                taskRepository, projectionRepository, executionService, completionService, requeueSupport, leaseHeartbeat, 600);

        runner.run(task.getPublicId(), "projection-worker");

        verify(executionService).execute(projection.getId(), task.getWorkspaceId());
        verify(completionService).complete(task.getPublicId(), "projection-worker", task.getAttemptCount());
    }

    /**
     * L2 缺失时不得标记 succeeded，应清事实并失败。
     */
    @Test
    void failsWhenProjectionFactsIncomplete() {
        AsyncTaskRepository taskRepository = mock(AsyncTaskRepository.class);
        WorkspaceProjectionRepository projectionRepository = mock(WorkspaceProjectionRepository.class);
        WorkspaceProjectionExecutionService executionService = mock(WorkspaceProjectionExecutionService.class);
        WorkspaceProjectionCompletionService completionService = mock(WorkspaceProjectionCompletionService.class);
        ProjectionRequeueSupport requeueSupport = mock(ProjectionRequeueSupport.class);
        TaskLeaseHeartbeat leaseHeartbeat = mock(TaskLeaseHeartbeat.class);
        TaskLeaseHeartbeat.Guard guard = mock(TaskLeaseHeartbeat.Guard.class);

        AsyncTask task = AsyncTask.queuedProjection(7L, "projection:file:11:rules:v1", "{}");
        task.claim("projection-worker", OffsetDateTime.now().plusMinutes(1));
        when(taskRepository.findByPublicId(task.getPublicId())).thenReturn(Optional.of(task));

        WorkspaceProjection projection = WorkspaceProjection.queued(7L, task.getId(), "rules:v1");
        when(projectionRepository.findByAsyncTaskIdAndWorkspaceId(task.getId(), task.getWorkspaceId()))
                .thenReturn(Optional.of(projection));
        when(executionService.execute(projection.getId(), task.getWorkspaceId())).thenReturn(true);
        when(requeueSupport.isProjectionFactsComplete(task.getWorkspaceId(), projection.getId())).thenReturn(false);
        when(leaseHeartbeat.register(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(guard);

        WorkspaceProjectionTaskRunner runner = new WorkspaceProjectionTaskRunner(
                taskRepository, projectionRepository, executionService, completionService, requeueSupport, leaseHeartbeat, 600);

        runner.run(task.getPublicId(), "projection-worker");

        verify(requeueSupport).wipeAnalysisFacts(task.getWorkspaceId());
        verify(completionService).fail(
                task.getPublicId(),
                "projection-worker",
                task.getAttemptCount(),
                "PROJECTION_INCOMPLETE",
                "投影未写入 L2 表达标注。请执行 mvn clean compile 后完全重启后端再试。");
        verify(completionService, never()).complete(task.getPublicId(), "projection-worker");
    }

    /**
     * 租约 owner 不匹配的旧 Worker 必须安全返回，不能把新 Worker 的执行结果提前结束。
     */
    @Test
    void ignoresProjectionClaimedByAnotherWorker() {
        AsyncTaskRepository taskRepository = mock(AsyncTaskRepository.class);
        WorkspaceProjectionRepository projectionRepository = mock(WorkspaceProjectionRepository.class);
        WorkspaceProjectionExecutionService executionService = mock(WorkspaceProjectionExecutionService.class);
        WorkspaceProjectionCompletionService completionService = mock(WorkspaceProjectionCompletionService.class);
        ProjectionRequeueSupport requeueSupport = mock(ProjectionRequeueSupport.class);
        TaskLeaseHeartbeat leaseHeartbeat = mock(TaskLeaseHeartbeat.class);

        AsyncTask task = AsyncTask.queuedProjection(7L, "projection:file:11:rules:v1", "{}");
        task.claim("new-worker", OffsetDateTime.now().plusMinutes(1));
        when(taskRepository.findByPublicId(task.getPublicId())).thenReturn(Optional.of(task));

        WorkspaceProjectionTaskRunner runner = new WorkspaceProjectionTaskRunner(
                taskRepository, projectionRepository, executionService, completionService, requeueSupport, leaseHeartbeat, 600);

        runner.run(task.getPublicId(), "old-worker");

        verify(executionService, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(completionService, never()).complete(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
