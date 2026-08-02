package com.insightflow.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.dto.importing.ImportTaskResult;
import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.ImportFile;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.ImportFileRepository;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 固定 CSV 导入成功与自动投影命令之间的提交后边界，防止 Worker 读取尚未提交的反馈事实。
 */
class ImportTaskCompletionProjectionTriggerTest {

    /** 每个测试结束都清理线程本地事务同步，避免影响其它纯单元测试。 */
    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * 只有导入终态成功提交后才创建投影命令，且文件本身保持 processed 直到投影 Worker 接管。
     */
    @Test
    void enqueuesProjectionOnlyAfterSuccessfulImportCommit() throws Exception {
        AsyncTaskRepository taskRepository = mock(AsyncTaskRepository.class);
        ImportFileRepository fileRepository = mock(ImportFileRepository.class);
        WorkspaceProjectionCommandService projectionCommandService = mock(WorkspaceProjectionCommandService.class);
        AsyncTask task = AsyncTask.queuedImport(7L, 11L, "import:11", "{}");
        setId(task, 21L);
        task.claim("import-worker", OffsetDateTime.now().plusMinutes(1));
        ImportFile file = ImportFile.uploaded(7L, 3L, "7/file.csv", "file.csv", "text/csv", 10L, "a".repeat(64));
        setId(file, 11L);
        when(taskRepository.findByPublicId(task.getPublicId())).thenReturn(Optional.of(task));
        when(fileRepository.findByIdAndWorkspaceId(11L, 7L)).thenReturn(Optional.of(file));
        ImportTaskCompletionService service = new ImportTaskCompletionService(
                taskRepository, fileRepository, projectionCommandService);

        TransactionSynchronizationManager.initSynchronization();
        service.complete(task.getPublicId(), "import-worker", "{}", new ImportTaskResult(1, 0, 0, List.of()));
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();

        assertThat(task.getStatus()).isEqualTo("succeeded");
        assertThat(file.getStatus()).isEqualTo("processed");
        assertThat(synchronizations).hasSize(1);
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        verify(projectionCommandService).enqueueForImportedFile(7L, 11L);
    }

    /** 仓储 Mock 不会生成数据库 identity 主键；测试只模拟已持久化任务和文件的内部键。 */
    private void setId(Object target, long id) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(target, id);
    }
}
