package com.insightflow.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/** 验证异步 Worker 能从报告冻结范围中恢复精确的左右边界。 */
class ReportTimeRangeTest {

    @Test
    void readsFrozenStartAndEndFromReportScopeJson() {
        String scopeJson = """
                {"timeRange":{"start":"2026-08-01T00:00:00+08:00","end":"2026-08-08T00:00:00+08:00"}}
                """;

        ReportTimeRange range = ReportTimeRange.fromScopeJson(new ObjectMapper(), scopeJson);

        assertThat(range.start()).isEqualTo(OffsetDateTime.parse("2026-08-01T00:00:00+08:00"));
        assertThat(range.end()).isEqualTo(OffsetDateTime.parse("2026-08-08T00:00:00+08:00"));
    }
}
