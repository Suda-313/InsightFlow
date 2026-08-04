package com.insightflow.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;

/**
 * 报告任务从创建时冻结的 scope_json 恢复时间范围。
 * 该值是历史报告的输入事实，Worker 不得用当前时间或报告类型覆盖它。
 */
public record ReportTimeRange(OffsetDateTime start, OffsetDateTime end) {

    public ReportTimeRange {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new IllegalArgumentException("报告时间范围必须为有效的左闭右开区间");
        }
    }

    /** 读取由 ReportCommandService 写入的稳定 JSON 契约。 */
    public static ReportTimeRange fromScopeJson(ObjectMapper objectMapper, String scopeJson) {
        try {
            JsonNode range = objectMapper.readTree(scopeJson).path("timeRange");
            return new ReportTimeRange(
                    OffsetDateTime.parse(range.path("start").asText()),
                    OffsetDateTime.parse(range.path("end").asText()));
        } catch (Exception exception) {
            throw new IllegalArgumentException("报告时间范围缺失或格式不正确", exception);
        }
    }
}
