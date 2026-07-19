package com.insightflow.importing.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.insightflow.importing.application.FileImportService;
import com.insightflow.importing.application.ImportMapping;
import com.insightflow.importing.domain.AsyncTask;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * CSV 文件导入的 HTTP 边界。
 *
 * <p>路径中的 Workspace UUID 始终传入服务层做归属校验；接口不返回 MinIO 对象键、原始 CSV、
 * 文件哈希、内部主键或未脱敏样本。</p>
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/imports/files")
public class FileImportController {

    /**
     * 应用服务承担对象存储、映射校验、状态机和异步任务创建，Controller 不直接访问仓储。
     */
    private final FileImportService fileImportService;

    /**
     * 通过构造器注入用例服务，使 HTTP 契约可独立测试和替换。
     */
    public FileImportController(FileImportService fileImportService) {
        this.fileImportService = fileImportService;
    }

    /**
     * 上传一个待映射 CSV，并返回脱敏表头样例；V1 不接受 Excel。
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileImportService.ImportedFileView> upload(
            @PathVariable UUID workspaceId,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(fileImportService.upload(workspaceId, file));
    }

    /**
     * 查询一个文件的元数据、当前映射和受控脱敏预览。
     */
    @GetMapping("/{fileId}")
    public FileImportService.ImportedFileView get(
            @PathVariable UUID workspaceId,
            @PathVariable UUID fileId) {
        return fileImportService.get(workspaceId, fileId);
    }

    /**
     * 保存本次文件的字段映射；服务端会再次按实际表头验证而不信任前端缓存。
     */
    @PostMapping("/{fileId}/mapping")
    public FileImportService.ImportedFileView saveMapping(
            @PathVariable UUID workspaceId,
            @PathVariable UUID fileId,
            @Valid @RequestBody MappingRequest request) {
        return fileImportService.saveMapping(workspaceId, fileId, request.mapping());
    }

    /**
     * 受理异步导入命令；同一 Workspace、命令类型和幂等键只会创建一个任务。
     */
    @PostMapping("/{fileId}/start")
    public ResponseEntity<TaskResponse> start(
            @PathVariable UUID workspaceId,
            @PathVariable UUID fileId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new com.insightflow.importing.application.ImportValidationException(
                    "Idempotency-Key 不能为空。");
        }
        AsyncTask task = fileImportService.start(workspaceId, fileId, idempotencyKey.trim());
        return ResponseEntity.accepted().body(TaskResponse.from(task));
    }

    /**
     * 返回文件最近一次导入任务的状态、计数和受限错误摘要，供前端轮询。
     */
    @GetMapping("/{fileId}/result")
    public FileImportService.ImportResultView getResult(
            @PathVariable UUID workspaceId,
            @PathVariable UUID fileId) {
        return fileImportService.getResult(workspaceId, fileId);
    }

    /**
     * 映射请求外层契约，与 API 文档的 mapping JSON 对象保持一致。
     */
    public record MappingRequest(@Valid @JsonProperty("mapping") ImportMapping mapping) {
    }

    /**
     * 异步命令的 202 响应，不把内部任务 id、payload 或错误栈暴露给调用方。
     */
    public record TaskResponse(
            UUID id,
            String type,
            String status,
            @JsonProperty("created_at") OffsetDateTime createdAt) {
        /**
         * 显式投影任务实体，避免未来任务表增加内部字段后 API 意外泄漏。
         */
        static TaskResponse from(AsyncTask task) {
            return new TaskResponse(task.getPublicId(), task.getTaskType(), task.getStatus(), task.getCreatedAt());
        }
    }
}
