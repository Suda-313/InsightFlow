package com.insightflow.dto.analysis;

import java.util.List;
import java.util.UUID;

/**
 * 自动投影任务的最小、不可变输入摘要。
 *
 * <p>payload 只保留公开文件 UUID 与规则版本，不含 CSV 正文、对象键、用户标识或未经脱敏的反馈文本。</p>
 */
public record ProjectionTaskPayload(List<UUID> fileIds, String ruleVersion) {
}
