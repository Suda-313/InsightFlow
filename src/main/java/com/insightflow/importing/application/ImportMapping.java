package com.insightflow.importing.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户确认的 CSV 列到 InsightFlow 规范字段的映射。
 *
 * <p>字段名遵循 API 的 snake_case，值必须是上传文件中已存在的表头。可选 dimensions 只承载
 * 渠道、版本等扩展维度，不能替代四个导入必填字段。</p>
 */
public record ImportMapping(
        @JsonProperty("feedback_text") String feedbackText,
        @JsonProperty("occurred_at") String occurredAt,
        @JsonProperty("source") String source,
        @JsonProperty("external_ref") String externalRef,
        @JsonProperty("dimensions") Map<String, String> dimensions) {

    /**
     * 返回所有被映射的 CSV 列，便于统一检查其是否出现在当前文件表头中。
     */
    public Map<String, String> allColumns() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("feedback_text", feedbackText);
        result.put("occurred_at", occurredAt);
        result.put("source", source);
        result.put("external_ref", externalRef);
        if (dimensions != null) {
            dimensions.forEach((dimension, column) -> result.put("dimensions." + dimension, column));
        }
        return result;
    }
}
