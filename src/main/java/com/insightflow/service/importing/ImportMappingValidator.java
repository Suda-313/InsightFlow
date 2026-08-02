package com.insightflow.service.importing;

import com.insightflow.common.exception.ImportValidationException;
import com.insightflow.dto.importing.ImportMapping;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 将用户映射约束为一份可确定执行的导入契约。
 *
 * <p>校验发生在创建异步任务之前，防止 Worker 消耗资源后才发现列不存在；错误中只返回字段名和
 * 表头名，不泄漏预览以外的行数据。</p>
 */
@Component
public class ImportMappingValidator {

    /**
     * 映射字段和表头相同才允许持久化；四个规范字段必须分别映射到不同的列。
     */
    public void validate(ImportMapping mapping, List<String> headers) {
        List<ImportValidationException.FieldError> errors = new ArrayList<>();
        if (mapping == null) {
            throw new ImportValidationException("必须提交字段映射。", List.of(
                    new ImportValidationException.FieldError("mapping", "不能为空")));
        }
        Set<String> mappedRequiredColumns = new HashSet<>();
        mapping.allColumns().forEach((field, column) -> validateColumn(field, column, headers, errors));
        collectRequiredColumn(mapping.feedbackText(), "feedback_text", mappedRequiredColumns, errors);
        collectRequiredColumn(mapping.occurredAt(), "occurred_at", mappedRequiredColumns, errors);
        collectRequiredColumn(mapping.source(), "source", mappedRequiredColumns, errors);
        collectRequiredColumn(mapping.externalRef(), "external_ref", mappedRequiredColumns, errors);
        if (mappedRequiredColumns.size() != 4) {
            errors.add(new ImportValidationException.FieldError(
                    "mapping", "四个必填字段必须映射到不同的 CSV 列"));
        }
        if (mapping.dimensions() != null) {
            mapping.dimensions().keySet().forEach(dimension -> {
                if (!dimension.matches("[a-z][a-z0-9_]{0,39}")) {
                    errors.add(new ImportValidationException.FieldError(
                            "dimensions." + dimension, "维度键必须为小写字母、数字或下划线"));
                }
            });
        }
        if (!errors.isEmpty()) {
            throw new ImportValidationException("字段映射校验失败。", errors);
        }
    }

    /**
     * 检查一个映射值既非空又存在于当前文件表头中。
     */
    private void validateColumn(
            String field,
            String column,
            List<String> headers,
            List<ImportValidationException.FieldError> errors) {
        if (column == null || column.isBlank()) {
            errors.add(new ImportValidationException.FieldError(field, "必须选择 CSV 列"));
        } else if (!headers.contains(column)) {
            errors.add(new ImportValidationException.FieldError(field, "映射列不存在于当前文件表头"));
        }
    }

    /**
     * 记录必填列，用 Set 检查一个 CSV 列被重复承担多个关键业务语义的错误。
     */
    private void collectRequiredColumn(
            String column,
            String field,
            Set<String> columns,
            List<ImportValidationException.FieldError> errors) {
        if (column != null && !column.isBlank() && !columns.add(column)) {
            errors.add(new ImportValidationException.FieldError(field, "不能与其它必填字段复用同一列"));
        }
    }
}
