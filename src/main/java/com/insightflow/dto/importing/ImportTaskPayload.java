package com.insightflow.dto.importing;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * 持久化在任务表中的最小导入输入摘要。
 *
 * <p>任务在受理时复制已经校验的映射，Worker 不再读取可变的 {@code import_file.mapping_json}。
 * payload 不保存 CSV 正文或真实 PII，原始文件仍只位于 MinIO。</p>
 */
public record ImportTaskPayload(
        @JsonProperty("file_id") UUID fileId,
        @JsonProperty("mapping") ImportMapping mapping) {
}
