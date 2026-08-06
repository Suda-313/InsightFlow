package com.insightflow.task;

import com.insightflow.dto.importing.ImportTaskResult;
import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.ImportFile;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.ImportFileRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在独立短事务中收敛导入终态，确保解析阶段的异常不会回滚失败标记。
 */
@Service
public class ImportTaskCompletionService {

    /** 任务仓储按公开 UUID读取状态，并在写终态前验证当前租约 owner。 */
    private final AsyncTaskRepository taskRepository;

    /** 文件仓储在同一完成事务内投影 processed 或 failed 状态。 */
    private final ImportFileRepository importFileRepository;

    /** 导入成功提交后创建看板投影命令；该服务不会在 CSV Worker 长事务内执行投影。 */
    private final WorkspaceProjectionCommandService projectionCommandService;

    /** 构造完成服务，Worker 不直接在长 IO 流程中持有数据库事务。 */
    public ImportTaskCompletionService(
            AsyncTaskRepository taskRepository,
            ImportFileRepository importFileRepository,
            WorkspaceProjectionCommandService projectionCommandService) {
        this.taskRepository = taskRepository;
        this.importFileRepository = importFileRepository;
        this.projectionCommandService = projectionCommandService;
    }

    /**
     * 用受控结果摘要写入成功或部分失败；失效 Worker 的旧结果会被忽略，不能覆盖新租约。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID taskPublicId, String workerId, String resultJson, ImportTaskResult result) {
        complete(taskPublicId, workerId, -1, resultJson, result);
    }

    /** Execution version prevents an expired worker from finalizing a task reclaimed by another worker. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID taskPublicId, String workerId, int executionVersion, String resultJson, ImportTaskResult result) {
        AsyncTask task = taskRepository.findByPublicId(taskPublicId).orElse(null);
        if (task == null || !ownsLease(task, workerId, executionVersion)) {
            return;
        }
        ImportFile file = importFileRepository.findByIdAndWorkspaceId(task.getImportFileId(), task.getWorkspaceId())
                .orElse(null);
        if (file == null) {
            task.markFailed("IMPORT_FILE_NOT_FOUND", "导入文件不存在或不属于当前工作区。");
            return;
        }
        if (result.failedCount() == 0) {
            task.markSucceeded(resultJson);
            file.markProcessed();
            enqueueProjectionAfterCommit(task, file);
        } else if (result.importedCount() + result.duplicateCount() > 0) {
            task.markPartialFailed(resultJson);
            file.markProcessed();
            enqueueProjectionAfterCommit(task, file);
        } else {
            task.markPartialFailed(resultJson);
            file.markFailed();
        }
    }

    /**
     * 任务级异常只写固定错误码和无 PII 摘要；同样要求当前 Worker 仍持有租约。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID taskPublicId, String workerId, String code, String message) {
        fail(taskPublicId, workerId, -1, code, message);
    }

    /** A timeout may only fail the exact execution which observed it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID taskPublicId, String workerId, int executionVersion, String code, String message) {
        AsyncTask task = taskRepository.findByPublicId(taskPublicId).orElse(null);
        if (task == null || !ownsLease(task, workerId, executionVersion)) {
            return;
        }
        task.markFailed(code, message);
        importFileRepository.findByIdAndWorkspaceId(task.getImportFileId(), task.getWorkspaceId())
                .ifPresent(ImportFile::markFailed);
    }

    /**
     * 只有导入终态和文件 processed 状态真实提交后才受理投影，避免 Worker 读取到回滚的反馈事实。
     */
    private void enqueueProjectionAfterCommit(AsyncTask task, ImportFile file) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    /** 提交后在新的命令事务中创建幂等投影任务。 */
                    @Override
                    public void afterCommit() {
                        projectionCommandService.enqueueForImportedFile(task.getWorkspaceId(), file.getId());
                    }
                });
    }

    private boolean ownsLease(AsyncTask task, String workerId, int executionVersion) {
        return executionVersion < 0 ? task.isLeaseOwnedBy(workerId) : task.isLeaseOwnedBy(workerId, executionVersion);
    }
}
