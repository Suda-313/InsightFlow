package com.insightflow.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * 固定只读报告自身的执行状态，确保它不借用或改写导入文件和投影任务状态。
 */
class AnalysisReportStateTest {

    /**
     * 报告任务只从 queued 走到 running 和 succeeded，并只保存自己的结构化快照。
     */
    @Test
    void tracksReportLifecycleIndependentlyFromProjectionFacts() {
        AnalysisReport report = AnalysisReport.queued(4L, 8L, "report-v1", OffsetDateTime.now(), "{}");

        assertThat(report.getStatus()).isEqualTo("queued");

        report.markRunning();
        assertThat(report.getStatus()).isEqualTo("running");

        report.markSucceeded("{\"issues\":[]}");
        assertThat(report.getStatus()).isEqualTo("succeeded");
        assertThat(report.getReportJson()).isEqualTo("{\"issues\":[]}");
    }
}
