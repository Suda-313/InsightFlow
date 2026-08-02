package com.insightflow.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.ImportFile;
import com.insightflow.entity.ProjectionFile;
import com.insightflow.entity.WorkspaceProjection;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.ImportFileRepository;
import com.insightflow.repository.ProjectionFileRepository;
import com.insightflow.repository.WorkspaceProjectionRepository;
import java.lang.reflect.Field;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 固定导入完成后自动创建投影命令的幂等边界；同一来源文件不能重复进入看板计算队列。
 */
class WorkspaceProjectionCommandServiceTest {

    /**
     * 首次受理会冻结文件关联和规则版本，重复受理则直接返回已有任务。
     */
    @Test
    void createsExactlyOneProjectionTaskForOneProcessedFile() throws Exception {
        ImportFileRepository fileRepository = mock(ImportFileRepository.class);
        AsyncTaskRepository taskRepository = mock(AsyncTaskRepository.class);
        WorkspaceProjectionRepository projectionRepository = mock(WorkspaceProjectionRepository.class);
        ProjectionFileRepository projectionFileRepository = mock(ProjectionFileRepository.class);
        WorkspaceProjectionScheduler scheduler = mock(WorkspaceProjectionScheduler.class);
        ProjectionRequeueSupport requeueSupport = mock(ProjectionRequeueSupport.class);
        ImportFile file = processedFile();
        setId(file, 11L);
        AsyncTask task = AsyncTask.queuedProjection(7L, "projection:file:11:rules:v1", "{}");
        setId(task, 21L);

        when(fileRepository.findByIdAndWorkspaceIdForUpdate(11L, 7L)).thenReturn(Optional.of(file));
        when(taskRepository.findByWorkspaceIdAndTaskTypeAndIdempotencyKey(
                7L, "projection", "projection:file:11:rules:v1"))
                .thenReturn(Optional.empty(), Optional.of(task));
        when(requeueSupport.isHealthyProjection(7L, task)).thenReturn(true);
        when(taskRepository.saveAndFlush(any(AsyncTask.class))).thenReturn(task);
        when(projectionRepository.saveAndFlush(any(WorkspaceProjection.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        WorkspaceProjectionCommandService service = new WorkspaceProjectionCommandService(
                fileRepository, taskRepository, projectionRepository, projectionFileRepository,
                new ObjectMapper(), scheduler, requeueSupport, "rules:v1");

        AsyncTask created = service.enqueueForImportedFile(7L, 11L);
        AsyncTask repeated = service.enqueueForImportedFile(7L, 11L);

        assertThat(created).isSameAs(task);
        assertThat(repeated).isSameAs(task);
        assertThat(file.getProjectionStatus()).isEqualTo("pending");
        verify(taskRepository).saveAndFlush(any(AsyncTask.class));
        verify(projectionRepository).saveAndFlush(any(WorkspaceProjection.class));
        verify(projectionFileRepository).saveAndFlush(any(ProjectionFile.class));
        verify(scheduler).dispatchClaimableTasks();
        verify(taskRepository, never()).saveAndFlush(eq(created));
    }

    /**
     * 已成功但缺少 L2 的旧任务应被拆除并创建新投影命令。
     */
    @Test
    void reEnqueuesWhenExistingProjectionIsIncomplete() throws Exception {
        ImportFileRepository fileRepository = mock(ImportFileRepository.class);
        AsyncTaskRepository taskRepository = mock(AsyncTaskRepository.class);
        WorkspaceProjectionRepository projectionRepository = mock(WorkspaceProjectionRepository.class);
        ProjectionFileRepository projectionFileRepository = mock(ProjectionFileRepository.class);
        WorkspaceProjectionScheduler scheduler = mock(WorkspaceProjectionScheduler.class);
        ProjectionRequeueSupport requeueSupport = mock(ProjectionRequeueSupport.class);
        ImportFile file = processedFile();
        setId(file, 11L);
        AsyncTask staleTask = AsyncTask.queuedProjection(7L, "projection:file:11:rules:v1", "{}");
        setId(staleTask, 21L);
        staleTask.claim("worker", java.time.OffsetDateTime.now().plusMinutes(5));
        staleTask.markSucceeded("{}");

        AsyncTask freshTask = AsyncTask.queuedProjection(7L, "projection:file:11:rules:v1", "{}");
        setId(freshTask, 22L);

        when(fileRepository.findByIdAndWorkspaceIdForUpdate(11L, 7L)).thenReturn(Optional.of(file));
        when(taskRepository.findByWorkspaceIdAndTaskTypeAndIdempotencyKey(
                7L, "projection", "projection:file:11:rules:v1"))
                .thenReturn(Optional.of(staleTask));
        when(requeueSupport.isHealthyProjection(7L, staleTask)).thenReturn(false);
        when(taskRepository.saveAndFlush(any(AsyncTask.class))).thenReturn(freshTask);
        when(projectionRepository.saveAndFlush(any(WorkspaceProjection.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        WorkspaceProjectionCommandService service = new WorkspaceProjectionCommandService(
                fileRepository, taskRepository, projectionRepository, projectionFileRepository,
                new ObjectMapper(), scheduler, requeueSupport, "rules:v1");

        AsyncTask created = service.enqueueForImportedFile(7L, 11L);

        assertThat(created).isSameAs(freshTask);
        verify(requeueSupport).removeProjectionChain(7L, staleTask);
        verify(requeueSupport).wipeAnalysisFacts(7L);
        verify(taskRepository).saveAndFlush(any(AsyncTask.class));
    }

    /**
     * 生成一个已经成功写入脱敏反馈的来源文件，投影命令不得接受未导入文件。
     */
    private ImportFile processedFile() {
        ImportFile file = ImportFile.uploaded(7L, 3L, "7/file.csv", "file.csv", "text/csv", 10L, "a".repeat(64));
        file.markMapped("{}");
        file.markProcessing();
        file.markProcessed();
        return file;
    }

    /**
     * 仓储 Mock 不会生成 identity 主键；测试只补充数据库在 saveAndFlush 后必然存在的内部键。
     */
    private void setId(Object target, long id) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(target, id);
    }
}
