package com.insightflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.common.exception.ImportFileNotFoundException;
import com.insightflow.common.exception.ImportValidationException;
import com.insightflow.dto.importing.ImportMapping;
import com.insightflow.dto.importing.ImportTaskResult;
import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.FeedbackSource;
import com.insightflow.entity.ImportFile;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.FeedbackSourceRepository;
import com.insightflow.repository.ImportFileRepository;
import com.insightflow.service.importing.CsvPreviewReader;
import com.insightflow.service.importing.HashingService;
import com.insightflow.service.importing.ImportMappingValidator;
import com.insightflow.storage.RawImportObjectStorage;
import com.insightflow.task.ImportTaskCommandService;
import com.insightflow.entity.Workspace;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * CSV 上传、预览、字段映射和任务创建的应用服务。
 *
 * <p>此层是文件 API 的唯一业务入口：它先验证 Workspace 和文件，再调用 MinIO 写原始对象，最后
 * 创建可轮询任务。异步 Worker 只能从已 mapped 的文件和已提交任务开始执行。</p>
 */
@Service
@Transactional(readOnly = true)
public class FileImportService {

    /**
     * 先取得 Workspace 聚合，避免任何文件或任务仓储查询脱离一级隔离边界。
     */
    private final WorkspaceService workspaceService;

    /**
     * CSV 来源仓储用于在每个 Workspace 内复用固定 file_import 来源。
     */
    private final FeedbackSourceRepository feedbackSourceRepository;

    /**
     * 文件元数据仓储只允许按 Workspace 与公开 UUID 联合读取。
     */
    private final ImportFileRepository importFileRepository;

    /**
     * 任务仓储实现幂等任务创建与文件结果读取。
     */
    private final AsyncTaskRepository taskRepository;

    /**
     * 原始对象存储端口负责 MinIO 写入和后续受控读取。
     */
    private final RawImportObjectStorage objectStorage;

    /**
     * 预览读取器使用成熟 CSV 库并对样例应用 PII 脱敏。
     */
    private final CsvPreviewReader csvPreviewReader;

    /**
     * 映射校验器在创建异步任务前确认所有必填列真实存在。
     */
    private final ImportMappingValidator mappingValidator;

    /**
     * 统一 SHA-256 计算，文件校验与行级哈希不自己重复实现摘要算法。
     */
    private final HashingService hashingService;

    /**
     * Spring 统一的 JSON 序列化器，确保 JSONB 字段与 API DTO 保持一致。
     */
    private final ObjectMapper objectMapper;

    /**
     * 命令服务用独立事务锁定文件并创建任务，唯一键冲突后可在外层新事务可靠读取既有任务。
     */
    private final ImportTaskCommandService importTaskCommandService;

    /**
     * 构造用例服务，所有副作用依赖均显式注入，以便可用测试替身覆盖。
     */
    public FileImportService(
            WorkspaceService workspaceService,
            FeedbackSourceRepository feedbackSourceRepository,
            ImportFileRepository importFileRepository,
            AsyncTaskRepository taskRepository,
            RawImportObjectStorage objectStorage,
            CsvPreviewReader csvPreviewReader,
            ImportMappingValidator mappingValidator,
            HashingService hashingService,
            ObjectMapper objectMapper,
            ImportTaskCommandService importTaskCommandService) {
        this.workspaceService = workspaceService;
        this.feedbackSourceRepository = feedbackSourceRepository;
        this.importFileRepository = importFileRepository;
        this.taskRepository = taskRepository;
        this.objectStorage = objectStorage;
        this.csvPreviewReader = csvPreviewReader;
        this.mappingValidator = mappingValidator;
        this.hashingService = hashingService;
        this.objectMapper = objectMapper;
        this.importTaskCommandService = importTaskCommandService;
    }

    /**
     * 上传 CSV 并返回脱敏表头/样例；原始文件成功存入 MinIO 后才写入 import_file 元数据。
     */
    @Transactional
    public ImportedFileView upload(UUID workspacePublicId, MultipartFile multipartFile) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        validateCsv(multipartFile);
        CsvPreviewReader.CsvPreview preview = readPreview(multipartFile);
        String checksum = calculateChecksum(multipartFile);
        FeedbackSource source = feedbackSourceRepository
                .findByWorkspaceIdAndSourceType(workspace.getId(), "file_import")
                .orElseGet(() -> feedbackSourceRepository.save(FeedbackSource.fileImportSource(workspace.getId())));
        String objectKey = "workspaces/" + workspace.getPublicId() + "/imports/" + UUID.randomUUID() + ".csv";
        storeOriginalFile(multipartFile, objectKey);
        ImportFile saved = importFileRepository.save(ImportFile.uploaded(
                workspace.getId(),
                source.getId(),
                objectKey,
                safeFilename(multipartFile.getOriginalFilename()),
                "text/csv",
                multipartFile.getSize(),
                checksum));
        return ImportedFileView.from(saved, preview, null);
    }

    /**
     * 返回文件元数据和每次实时读取的少量脱敏预览，不返回可下载原文的对象 URL。
     */
    public ImportedFileView get(UUID workspacePublicId, UUID filePublicId) {
        ImportFile file = getFile(workspacePublicId, filePublicId);
        try (InputStream stream = objectStorage.open(file.getObjectKey())) {
            return ImportedFileView.from(file, csvPreviewReader.preview(stream), readMapping(file));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to close import file preview stream", exception);
        }
    }

    /**
     * 校验并保存映射；mapped 状态是创建导入任务的硬前置条件。
     */
    @Transactional
    public ImportedFileView saveMapping(UUID workspacePublicId, UUID filePublicId, ImportMapping mapping) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        ImportFile file = importFileRepository.findByWorkspaceIdAndPublicIdForUpdate(workspace.getId(), filePublicId)
                .orElseThrow(() -> new ImportFileNotFoundException(filePublicId));
        if (!"uploaded".equals(file.getStatus()) && !"mapped".equals(file.getStatus())) {
            throw new ImportValidationException("当前文件状态不允许修改映射。");
        }
        CsvPreviewReader.CsvPreview preview;
        try (InputStream stream = objectStorage.open(file.getObjectKey())) {
            preview = csvPreviewReader.preview(stream);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to close import file preview stream", exception);
        }
        mappingValidator.validate(mapping, preview.headers());
        try {
            file.markMapped(objectMapper.writeValueAsString(mapping));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to persist import mapping", exception);
        }
        ImportFile saved = importFileRepository.save(file);
        return ImportedFileView.from(saved, preview, mapping);
    }

    /**
     * 受理一个幂等导入命令，并在事务提交后才交给异步 Worker 执行。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AsyncTask start(UUID workspacePublicId, UUID filePublicId, String idempotencyKey) {
        try {
            return importTaskCommandService.start(workspacePublicId, filePublicId, idempotencyKey);
        } catch (DataIntegrityViolationException exception) {
            return importTaskCommandService.resolveIdempotencyCollision(workspacePublicId, filePublicId, idempotencyKey);
        }
    }

    /**
     * 返回文件最近一次导入任务的受控结果；从未启动时结果为空而不是伪造完成状态。
     */
    public ImportResultView getResult(UUID workspacePublicId, UUID filePublicId) {
        ImportFile file = getFile(workspacePublicId, filePublicId);
        AsyncTask task = taskRepository
                .findFirstByWorkspaceIdAndImportFileIdOrderByCreatedAtDesc(file.getWorkspaceId(), file.getId())
                .orElse(null);
        return ImportResultView.from(file, task, readResult(task));
    }

    /**
     * 在目标 Workspace 内读取文件，避免 API 只按 file UUID 查询而产生越权泄漏。
     */
    private ImportFile getFile(UUID workspacePublicId, UUID filePublicId) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        return importFileRepository.findByWorkspaceIdAndPublicId(workspace.getId(), filePublicId)
                .orElseThrow(() -> new ImportFileNotFoundException(filePublicId));
    }

    /**
     * V1 只接受扩展名和 MIME 均符合 CSV 预期的文件；Excel 明确留到 CSV 主路径稳定后实现。
     */
    private void validateCsv(MultipartFile multipartFile) {
        if (multipartFile == null || multipartFile.isEmpty() || multipartFile.getSize() <= 0) {
            throw new ImportValidationException("上传文件不能为空。");
        }
        String filename = safeFilename(multipartFile.getOriginalFilename());
        String contentType = multipartFile.getContentType();
        boolean validContentType = contentType == null
                || contentType.equalsIgnoreCase("text/csv")
                || contentType.equalsIgnoreCase("application/csv")
                || contentType.equalsIgnoreCase("application/vnd.ms-excel");
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".csv") || !validContentType) {
            throw new ImportValidationException("V1 当前只支持 UTF-8 CSV 文件。");
        }
    }

    /**
     * 先读取预览验证 CSV 结构；失败时不上传不完整或不可映射的原始对象。
     */
    private CsvPreviewReader.CsvPreview readPreview(MultipartFile multipartFile) {
        try (InputStream stream = multipartFile.getInputStream()) {
            return csvPreviewReader.preview(stream);
        } catch (IOException exception) {
            throw new ImportValidationException("无法读取上传文件。");
        }
    }

    /**
     * 单独读取流计算完整文件 SHA-256；MultipartFile 可重复打开流，避免缓存完整字节数组。
     */
    private String calculateChecksum(MultipartFile multipartFile) {
        try (InputStream stream = multipartFile.getInputStream()) {
            return hashingService.sha256(stream);
        } catch (IOException exception) {
            throw new ImportValidationException("无法校验上传文件。");
        }
    }

    /**
     * 将原始 CSV 写入受控对象存储；服务层不把对象存储路径返回到 API 响应。
     */
    private void storeOriginalFile(MultipartFile multipartFile, String objectKey) {
        try (InputStream stream = multipartFile.getInputStream()) {
            objectStorage.put(objectKey, stream, multipartFile.getSize(), "text/csv");
        } catch (IOException exception) {
            throw new ImportValidationException("无法读取上传文件。");
        }
    }

    /**
     * 清理浏览器可能携带的路径，仅保留显示文件名；对象键从不由该名称拼接。
     */
    private String safeFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "upload.csv";
        }
        return originalFilename.replace('\\', '/').substring(originalFilename.replace('\\', '/').lastIndexOf('/') + 1);
    }

    /**
     * 将持久化映射反序列化为 API 可读对象；映射状态文件不应出现非法 JSON。
     */
    private ImportMapping readMapping(ImportFile file) {
        if (file.getMappingJson() == null) {
            return null;
        }
        try {
            return objectMapper.readValue(file.getMappingJson(), ImportMapping.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored import mapping is invalid", exception);
        }
    }

    /**
     * 解析任务结果 JSON；损坏结果不向前端暴露堆栈，而是显示空摘要供后续排障。
     */
    private ImportTaskResult readResult(AsyncTask task) {
        if (task == null || task.getResultJson() == null) {
            return null;
        }
        try {
            return objectMapper.readValue(task.getResultJson(), ImportTaskResult.class);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    /**
     * 文件 API 视图只包含公开 UUID、受控样例和映射，不含内部键、对象键或文件摘要。
     */
    public record ImportedFileView(
            UUID id,
            @com.fasterxml.jackson.annotation.JsonProperty("original_filename") String originalFilename,
            @com.fasterxml.jackson.annotation.JsonProperty("size_bytes") long sizeBytes,
            String status,
            List<String> headers,
            List<java.util.Map<String, String>> samples,
            ImportMapping mapping,
            @com.fasterxml.jackson.annotation.JsonProperty("created_at") java.time.OffsetDateTime createdAt,
            @com.fasterxml.jackson.annotation.JsonProperty("updated_at") java.time.OffsetDateTime updatedAt) {
        /**
         * 将 JPA 实体和脱敏预览显式投影为 API 视图，避免实体字段扩展时意外泄漏。
         */
        static ImportedFileView from(ImportFile file, CsvPreviewReader.CsvPreview preview, ImportMapping mapping) {
            return new ImportedFileView(
                    file.getPublicId(), file.getOriginalFilename(), file.getSizeBytes(), file.getStatus(),
                    preview.headers(), preview.samples(), mapping, file.getCreatedAt(), file.getUpdatedAt());
        }
    }

    /**
     * 文件结果 API 视图只返回任务公开 UUID、状态与安全计数，原始 payload 和堆栈不在此暴露。
     */
    public record ImportResultView(
            @com.fasterxml.jackson.annotation.JsonProperty("file_id") UUID fileId,
            @com.fasterxml.jackson.annotation.JsonProperty("file_status") String fileStatus,
            @com.fasterxml.jackson.annotation.JsonProperty("task_id") UUID taskId,
            @com.fasterxml.jackson.annotation.JsonProperty("task_status") String taskStatus,
            @com.fasterxml.jackson.annotation.JsonProperty("imported_count") Integer importedCount,
            @com.fasterxml.jackson.annotation.JsonProperty("duplicate_count") Integer duplicateCount,
            @com.fasterxml.jackson.annotation.JsonProperty("failed_count") Integer failedCount,
            List<String> errors,
            @com.fasterxml.jackson.annotation.JsonProperty("error_code") String errorCode,
            @com.fasterxml.jackson.annotation.JsonProperty("error_message") String errorMessage) {
        /**
         * 将实体和可选结果摘要组合为前端可展示的数据，尚未启动任务时返回 null 任务字段。
         */
        static ImportResultView from(ImportFile file, AsyncTask task, ImportTaskResult result) {
            return new ImportResultView(
                    file.getPublicId(), file.getStatus(),
                    task == null ? null : task.getPublicId(), task == null ? null : task.getStatus(),
                    result == null ? null : result.importedCount(),
                    result == null ? null : result.duplicateCount(),
                    result == null ? null : result.failedCount(),
                    result == null ? List.of() : result.errors(),
                    task == null ? null : task.getErrorCode(),
                    task == null ? null : task.getErrorMessage());
        }
    }
}
