package com.insightflow.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.common.exception.ImportValidationException;
import com.insightflow.dto.importing.ImportMapping;
import com.insightflow.dto.importing.ImportTaskPayload;
import com.insightflow.dto.importing.ImportTaskResult;
import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.FeedbackEvent;
import com.insightflow.entity.ImportFile;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.FeedbackEventRepository;
import com.insightflow.repository.ImportFileRepository;
import com.insightflow.service.importing.CsvFormatSupport;
import com.insightflow.service.importing.HashingService;
import com.insightflow.service.importing.PiiSanitizer;
import com.insightflow.storage.RawImportObjectStorage;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 受控执行 CSV 导入任务的单体内 Worker。
 *
 * <p>执行器只读取任务所绑定的 import_file，逐行完成映射、脱敏、规范化和外部引用去重；它不调用
 * LLM、不访问任意 URL，也不把原始 CSV 或 PII 写入 PostgreSQL。</p>
 */
@Component
public class ImportTaskRunner {

    /**
     * 任务仓储提供持久化状态机，确保页面看到 queued/running/final 状态而不是线程黑盒。
     */
    private final AsyncTaskRepository taskRepository;

    /**
     * 文件仓储用于检查任务与文件的 Workspace 归属以及更新文件生命周期状态。
     */
    private final ImportFileRepository importFileRepository;

    /**
     * 反馈仓储提供外部引用幂等检查和脱敏事实写入。
     */
    private final FeedbackEventRepository feedbackEventRepository;

    /**
     * 原始对象端口只按已验证 object key 打开文件流，不允许任意对象访问。
     */
    private final RawImportObjectStorage objectStorage;

    /**
     * PII 脱敏规则在预览和真正写入之间保持同一个实现与替换口径。
     */
    private final PiiSanitizer piiSanitizer;

    /**
     * 哈希服务生成外部引用与规范化内容的不可逆摘要。
     */
    private final HashingService hashingService;

    /**
     * 使用 Spring 统一配置的 JSON 工具序列化映射、payload 和结果摘要。
     */
    private final ObjectMapper objectMapper;

    /**
     * 独立完成服务在新事务中保存终态，避免对象存储或行写入异常回滚失败标记。
     */
    private final ImportTaskCompletionService completionService;

    /**
     * 共享 CSV 支持确保 Worker 与预览端对重复表头使用相同拒绝规则。
     */
    private final CsvFormatSupport csvFormatSupport;
    private final TaskLeaseHeartbeat leaseHeartbeat;
    /** Long CSV processing uses its own wall-clock budget. */
    private final java.time.Duration maxRuntime;

    /**
     * 失败摘要上限，防止一个坏文件把错误列表膨胀成新的敏感数据载体。
     */
    private final int taskErrorLimit;

    /**
     * 构造 Worker；所有依赖均明确注入，便于后续以固定 CSV 样本做 Harness 回放。
     */
    public ImportTaskRunner(
            AsyncTaskRepository taskRepository,
            ImportFileRepository importFileRepository,
            FeedbackEventRepository feedbackEventRepository,
            RawImportObjectStorage objectStorage,
            PiiSanitizer piiSanitizer,
            HashingService hashingService,
            ObjectMapper objectMapper,
            ImportTaskCompletionService completionService,
            CsvFormatSupport csvFormatSupport,
            TaskLeaseHeartbeat leaseHeartbeat,
            @org.springframework.beans.factory.annotation.Value("${insightflow.task.import-max-runtime-seconds:1800}") long maxRuntimeSeconds,
            @org.springframework.beans.factory.annotation.Value("${insightflow.import.task-error-limit}") int taskErrorLimit) {
        this.taskRepository = taskRepository;
        this.importFileRepository = importFileRepository;
        this.feedbackEventRepository = feedbackEventRepository;
        this.objectStorage = objectStorage;
        this.piiSanitizer = piiSanitizer;
        this.hashingService = hashingService;
        this.objectMapper = objectMapper;
        this.completionService = completionService;
        this.csvFormatSupport = csvFormatSupport;
        this.leaseHeartbeat = leaseHeartbeat;
        this.maxRuntime = java.time.Duration.ofSeconds(maxRuntimeSeconds);
        this.taskErrorLimit = taskErrorLimit;
    }

    /**
     * 在受限线程池异步执行一次已提交的任务；重复调度已完成任务时安全返回，不重复写入反馈。
     */
    @Async("importTaskExecutor")
    public void run(UUID taskPublicId, String workerId) { run(taskPublicId, workerId, -1); }
    public void run(UUID taskPublicId, String workerId, int executionVersion) {
        AsyncTask task = taskRepository.findByPublicId(taskPublicId).orElse(null);
        int version = executionVersion < 0 ? task == null ? -1 : task.getAttemptCount() : executionVersion;
        if (task == null || !task.isLeaseOwnedBy(workerId, version)) {
            return;
        }
        TaskLeaseHeartbeat.Guard guard = leaseHeartbeat.register(taskPublicId, workerId, version, maxRuntime);
        try {
            guard.ensureActive();
            ImportTaskPayload payload = readPayload(task);
            ImportFile file = importFileRepository.findByIdAndWorkspaceId(task.getImportFileId(), task.getWorkspaceId())
                    .orElse(null);
            if (file == null || !file.getId().equals(task.getImportFileId())) {
                completionService.fail(
                        taskPublicId, workerId, version, "IMPORT_FILE_NOT_FOUND", "导入文件不存在或不属于当前工作区。");
                return;
            }
            if (!file.getPublicId().equals(payload.fileId()) || payload.mapping() == null) {
                completionService.fail(taskPublicId, workerId, version, "IMPORT_PAYLOAD_INVALID", "导入任务输入无效。");
                return;
            }
            ImportTaskResult result = importFile(file, task, payload.mapping(), guard);
            String resultJson = objectMapper.writeValueAsString(result);
            guard.ensureActive();
            completionService.complete(taskPublicId, workerId, version, resultJson, result);
        } catch (TaskLeaseHeartbeat.TaskExecutionTimeoutException timeout) {
            completionService.fail(taskPublicId, workerId, version, "TASK_EXECUTION_TIMEOUT", "导入任务超过最大执行时长。");
        } catch (TaskLeaseHeartbeat.TaskLeaseLostException lost) {
            return;
        } catch (Exception exception) {
            completionService.fail(
                    taskPublicId, workerId, version, "IMPORT_EXECUTION_FAILED", "导入执行失败，请检查 CSV 格式和字段映射。");
        } finally { leaseHeartbeat.unregister(taskPublicId, version); }
    }

    /**
     * 打开原始对象并逐行生成脱敏事件；任何行级异常只影响该行，不中断其它有效反馈。
     */
    private ImportTaskResult importFile(ImportFile file, AsyncTask task, ImportMapping mapping, TaskLeaseHeartbeat.Guard guard) throws Exception {
        int imported = 0;
        int duplicates = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        try (InputStream stream = objectStorage.open(file.getObjectKey());
                CSVParser parser = csvFormatSupport.parse(stream)) {
            Map<String, Integer> headerIndexes = csvFormatSupport.buildHeaderIndexes(parser.getHeaderNames());
            for (CSVRecord record : parser) {
                // Check before each row so lease loss cannot create additional feedback facts.
                guard.ensureActive();
                try {
                    FeedbackEvent event = toFeedbackEvent(record, headerIndexes, mapping, file, task);
                    try {
                        feedbackEventRepository.saveAndFlush(event);
                        imported++;
                    } catch (DataIntegrityViolationException exception) {
                        duplicates++;
                    }
                } catch (ImportValidationException exception) {
                    failed++;
                    addRowError(errors, record.getRecordNumber(), exception.getMessage());
                }
            }
        }
        return new ImportTaskResult(imported, duplicates, failed, List.copyOf(errors));
    }

    /**
     * 以已校验映射取值、脱敏并构建可分析事件；真实 external_ref 只在内存中用于哈希。
     */
    private FeedbackEvent toFeedbackEvent(
            CSVRecord record,
            Map<String, Integer> headerIndexes,
            ImportMapping mapping,
            ImportFile file,
            AsyncTask task) {
        String text = requiredValue(record, headerIndexes, mapping.feedbackText(), "feedback_text");
        String occurredAt = requiredValue(record, headerIndexes, mapping.occurredAt(), "occurred_at");
        String source = requiredValue(record, headerIndexes, mapping.source(), "source");
        String externalRef = requiredValue(record, headerIndexes, mapping.externalRef(), "external_ref");
        String sanitizedText = piiSanitizer.sanitize(text).trim();
        if (sanitizedText.isBlank()) {
            throw new ImportValidationException("反馈文本脱敏后为空。");
        }
        String normalizedText = normalize(sanitizedText);
        return FeedbackEvent.active(
                file.getWorkspaceId(),
                file.getSourceId(),
                hashingService.sha256(externalRef.trim()),
                parseOccurredAt(occurredAt),
                normalizeSourceKind(source),
                sanitizedText,
                normalizedText,
                serializeDimensions(record, headerIndexes, mapping),
                hashingService.sha256(normalizedText),
                task.getId());
    }

    /**
     * 读取必须存在且非空的列；错误中只点明规范字段，不回显该单元格原始内容。
     */
    private String requiredValue(
            CSVRecord record, Map<String, Integer> headerIndexes, String header, String field) {
        Integer index = headerIndexes.get(header);
        if (index == null || index >= record.size()) {
            throw new ImportValidationException(field + " 对应列缺失。");
        }
        String value = record.get(index);
        if (value == null || value.isBlank()) {
            throw new ImportValidationException(field + " 不能为空。");
        }
        return value;
    }

    /**
     * 解析 ISO 8601 优先格式，并兼容常见的无时区本地时间（按 UTC 解释）用于演示 CSV。
     */
    private OffsetDateTime parseOccurredAt(String value) {
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        .atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException exception) {
                throw new ImportValidationException("occurred_at 必须是 ISO 8601 或 yyyy-MM-dd HH:mm:ss 时间。");
            }
        }
    }

    /**
     * 仅保存小写、限长且已脱敏的来源标签，避免把自由文本无限制地写入高频维度列。
     */
    private String normalizeSourceKind(String value) {
        String normalized = normalize(piiSanitizer.sanitize(value));
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    /**
     * 序列化用户确认的可选维度；每个值同样脱敏，空值不进入统计维度。
     */
    private String serializeDimensions(
            CSVRecord record, Map<String, Integer> headerIndexes, ImportMapping mapping) {
        Map<String, String> dimensions = new LinkedHashMap<>();
        if (mapping.dimensions() != null) {
            mapping.dimensions().forEach((key, header) -> {
                Integer index = headerIndexes.get(header);
                if (index != null && index < record.size()) {
                    String value = piiSanitizer.sanitize(record.get(index));
                    if (value != null && !value.isBlank()) {
                        dimensions.put(key, value.trim());
                    }
                }
            });
        }
        try {
            return objectMapper.writeValueAsString(dimensions);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize import dimensions", exception);
        }
    }

    /**
     * 规范化脱敏文本，用于稳定哈希和后续规则主题归并，而不改变原始脱敏展示文本。
     */
    private String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * 将任务 payload 反序列化为最小关联信息；异常说明任务记录已损坏，应安全失败。
     */
    private ImportTaskPayload readPayload(AsyncTask task) {
        try {
            return objectMapper.readValue(task.getPayloadJson(), ImportTaskPayload.class);
        } catch (JsonProcessingException exception) {
            throw new ImportValidationException("导入任务输入无效。");
        }
    }

    /**
     * 保留有限行级错误；行号从 Commons CSV 记录号获取，不能携带原始文本。
     */
    private void addRowError(List<String> errors, long rowNumber, String reason) {
        if (errors.size() < taskErrorLimit) {
            errors.add("第 " + (rowNumber + 1) + " 行：" + reason);
        }
    }

    /**
     * 统一写入安全失败状态，并在已定位文件时同步标记其失败，防止页面长期停在 mapped。
     */
    private void failTask(AsyncTask task, ImportFile file, String code, String message) {
        task.markFailed(code, message);
        taskRepository.save(task);
        if (file != null) {
            file.markFailed();
            importFileRepository.save(file);
        }
    }

    /**
     * 去除 Excel 写入 UTF-8 CSV 时首列可能带上的 BOM。
     */
    private String stripBom(String header) {
        return header != null && header.startsWith("\uFEFF") ? header.substring(1) : header;
    }
}
