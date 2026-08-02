package com.insightflow.service.analysis;

import com.insightflow.service.DashboardService.DataCoverage;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 解析 Dashboard / 数据分析页共用的「分析日期范围」。
 *
 * <p>默认窗口锚定在数据覆盖 {@code windowEnd} 往前 {@link #DEFAULT_DAYS} 天，而非 wall-clock
 * 今天——批量导入历史 CSV 时后者会把有效数据全部滤掉。显式 {@code from}/{@code to} 优先，
 * 并 clamp 到 coverage 边界内。</p>
 */
public final class AnalysisWindowResolver {

    /** 未传参时的默认分析跨度（天）。 */
    public static final int DEFAULT_DAYS = 7;

    private AnalysisWindowResolver() {
    }

    /** 闭区间分析窗口；{@code start} 与 {@code end} 均为 {@code feedback_event.occurred_at} 可比时间点。 */
    public record AnalysisWindow(OffsetDateTime start, OffsetDateTime end) {
    }

    /**
     * 解析分析窗口：显式 {@code from}/{@code to}（含首尾日）优先；否则默认锚定 coverage 截止日。
     */
    public static AnalysisWindow resolve(DataCoverage coverage, LocalDate from, LocalDate to) {
        if (from != null && to != null) {
            OffsetDateTime start = from.atStartOfDay().atOffset(ZoneOffset.UTC);
            OffsetDateTime end = to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC).minusNanos(1);
            return clampToCoverage(new AnalysisWindow(start, end), coverage);
        }
        return resolveDefault(coverage);
    }

    /** 「全部」：使用 coverage 全范围；无覆盖数据时回退默认窗口。 */
    public static AnalysisWindow resolveFull(DataCoverage coverage) {
        if (coverage.windowStart() != null && coverage.windowEnd() != null) {
            return new AnalysisWindow(coverage.windowStart(), coverage.windowEnd());
        }
        return resolveDefault(coverage);
    }

    /** 判断 {@code occurredAt} 是否落在分析窗口内（闭区间）。 */
    public static boolean contains(AnalysisWindow window, OffsetDateTime occurredAt) {
        return occurredAt != null
                && !occurredAt.isBefore(window.start())
                && !occurredAt.isAfter(window.end());
    }

    private static AnalysisWindow resolveDefault(DataCoverage coverage) {
        if (coverage.windowEnd() != null) {
            OffsetDateTime end = coverage.windowEnd();
            OffsetDateTime start = end.minusDays(DEFAULT_DAYS);
            if (coverage.windowStart() != null && start.isBefore(coverage.windowStart())) {
                start = coverage.windowStart();
            }
            return new AnalysisWindow(start, end);
        }
        OffsetDateTime end = OffsetDateTime.now(ZoneOffset.UTC);
        return new AnalysisWindow(end.minusDays(DEFAULT_DAYS), end);
    }

    private static AnalysisWindow clampToCoverage(AnalysisWindow window, DataCoverage coverage) {
        OffsetDateTime start = window.start();
        OffsetDateTime end = window.end();
        if (coverage.windowStart() != null && start.isBefore(coverage.windowStart())) {
            start = coverage.windowStart();
        }
        if (coverage.windowEnd() != null && end.isAfter(coverage.windowEnd())) {
            end = coverage.windowEnd();
        }
        if (start.isAfter(end)) {
            return new AnalysisWindow(end, end);
        }
        return new AnalysisWindow(start, end);
    }
}
