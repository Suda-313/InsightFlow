package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.insightflow.service.DashboardService.DataCoverage;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class AnalysisWindowResolverTest {

    @Test
    void resolveDefaultAnchorsToCoverageEnd() {
        OffsetDateTime end = OffsetDateTime.parse("2026-07-11T12:00:00Z");
        OffsetDateTime start = OffsetDateTime.parse("2026-06-20T00:00:00Z");
        DataCoverage coverage = new DataCoverage(start, end, 100);

        AnalysisWindowResolver.AnalysisWindow window =
                AnalysisWindowResolver.resolve(coverage, null, null);

        assertThat(window.end()).isEqualTo(end);
        assertThat(window.start()).isEqualTo(end.minusDays(AnalysisWindowResolver.DEFAULT_DAYS));
    }

    @Test
    void resolveExplicitRangeClampsToCoverage() {
        OffsetDateTime coverageStart = OffsetDateTime.parse("2026-07-01T00:00:00Z");
        OffsetDateTime coverageEnd = OffsetDateTime.parse("2026-07-11T00:00:00Z");
        DataCoverage coverage = new DataCoverage(coverageStart, coverageEnd, 50);

        AnalysisWindowResolver.AnalysisWindow window = AnalysisWindowResolver.resolve(
                coverage, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-08-01"));

        assertThat(window.start()).isEqualTo(coverageStart);
        assertThat(window.end()).isEqualTo(coverageEnd);
    }

    @Test
    void resolveFullUsesCoverageBounds() {
        OffsetDateTime coverageStart = OffsetDateTime.parse("2026-06-27T00:00:00Z");
        OffsetDateTime coverageEnd = OffsetDateTime.parse("2026-07-11T00:00:00Z");
        DataCoverage coverage = new DataCoverage(coverageStart, coverageEnd, 50);

        AnalysisWindowResolver.AnalysisWindow window = AnalysisWindowResolver.resolveFull(coverage);

        assertThat(window.start()).isEqualTo(coverageStart);
        assertThat(window.end()).isEqualTo(coverageEnd);
    }
}
