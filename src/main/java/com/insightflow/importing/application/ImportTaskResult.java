package com.insightflow.importing.application;

import java.util.List;

/**
 * 导入结束时保存和返回的安全摘要。
 *
 * <p>错误列表最多保留配置规定的少量行号/原因，不包含任何原始单元格、外部工单号或脱敏前文本。</p>
 */
public record ImportTaskResult(
        int importedCount,
        int duplicateCount,
        int failedCount,
        List<String> errors) {
}
