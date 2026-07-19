package com.insightflow.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.ImportFile;
import com.insightflow.entity.ProjectionFile;
import com.insightflow.entity.WorkspaceProjection;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.ImportFileRepository;
import com.insightflow.repository.ProjectionFileRepository;
import com.insightflow.repository.WorkspaceProjectionRepository;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 验证投影 Worker 的终态只提交“已进入看板”的状态事实，不提前计算主题、趋势或预警。
 */
class WorkspaceProjectionCompletionServiceTest {

    /**
     * 持有有效租约的 Worker 完成后，同时结束任务、投影记录和来源文件投影状态。
     */
    @Test
    void completesProjectionWithoutChangingSuccessfulImportState() throws Exception {
        AsyncTaskRepository taskRepository = mock(AsyncTaskRepository.class);
        WorkspaceProjectionRepository projectionRepository = mock(WorkspaceProjectionRepository.class);
        ProjectionFileRepository projectionFileRepository = mock(ProjectionFileRepository.class);
        ImportFileRepository fileRepository = mock(ImportFileRepository.class);
        AsyncTask task = AsyncTask.queuedProjection(7L, "projection:file:11:rules:v1", "{}");
        setId(task, 21L);
        task.claim("projection-worker", OffsetDateTime.now().plusMinutes(1));
        WorkspaceProjection projection = WorkspaceProjection.queued(7L, 21L, "rules:v1");
        setId(projection, 31L);
        ImportFile file = processedFile();
        setId(file, 11L);

        when(taskRepository.findByPublicId(task.getPublicId())).thenReturn(Optional.of(task));
        when(projectionRepository.findByAsyncTaskIdAndWorkspaceId(21L, 7L)).thenReturn(Optional.of(projection));
        when(projectionFileRepository.findByWorkspaceProjectionIdAndWorkspaceId(31L, 7L))
                .thenReturn(List.of(ProjectionFile.of(31L, 7L, 11L)));
        when(fileRepository.findByIdAndWorkspaceId(11L, 7L)).thenReturn(Optional.of(file));

        WorkspaceProjectionCompletionService service = new WorkspaceProjectionCompletionService(
                taskRepository, projectionRepository, projectionFileRepository, fileRepository);

        service.complete(task.getPublicId(), "projection-worker");

        assertThat(task.getStatus()).isEqualTo("succeeded");
        assertThat(projection.getStatus()).isEqualTo("succeeded");
        assertThat(file.getStatus()).isEqualTo("processed");
        assertThat(file.getProjectionStatus()).isEqualTo("projected");
    }

    /** 创建已写入脱敏反馈的文件，投影完成不得将其倒退回映射或处理状态。 */
    private ImportFile processedFile() {
        ImportFile file = ImportFile.uploaded(7L, 3L, "7/file.csv", "file.csv", "text/csv", 10L, "a".repeat(64));
        file.markMapped("{}");
        file.markProcessing();
        file.markProcessed();
        return file;
    }

    /** 只为模拟数据库已分配的 identity 主键，生产代码不会通过反射写入实体。 */
    private void setId(Object target, long id) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(target, id);
    }
}
