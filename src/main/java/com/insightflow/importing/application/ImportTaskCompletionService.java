package com.insightflow.importing.application;

import com.insightflow.importing.domain.AsyncTask;
import com.insightflow.importing.domain.ImportFile;
import com.insightflow.importing.infrastructure.AsyncTaskRepository;
import com.insightflow.importing.infrastructure.ImportFileRepository;
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

    /** 构造完成服务，Worker 不直接在长 IO 流程中持有数据库事务。 */
    public ImportTaskCompletionService(
            AsyncTaskRepository taskRepository, ImportFileRepository importFileRepository) {
        this.taskRepository = taskRepository;
        this.importFileRepository = importFileRepository;
    }

    /**
     * 用受控结果摘要写入成功或部分失败；失效 Worker 的旧结果会被忽略，不能覆盖新租约。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID taskPublicId, String workerId, String resultJson, ImportTaskResult result) {
        AsyncTask task = taskRepository.findByPublicId(taskPublicId).orElse(null);
        if (task == null || !task.isLeaseOwnedBy(workerId)) {
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
        } else if (result.importedCount() + result.duplicateCount() > 0) {
            task.markPartialFailed(resultJson);
            file.markProcessed();
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
        AsyncTask task = taskRepository.findByPublicId(taskPublicId).orElse(null);
        if (task == null || !task.isLeaseOwnedBy(workerId)) {
            return;
        }
        task.markFailed(code, message);
        importFileRepository.findByIdAndWorkspaceId(task.getImportFileId(), task.getWorkspaceId())
                .ifPresent(ImportFile::markFailed);
    }
}
