package com.insightflow.report;

/** 运营报告的受控范围类型。 */
public enum OperationalReportScope {
    /** 聚焦当日的已确认调查证据。 */ DAILY,
    /** 聚焦周期内运营复盘的已确认调查证据。 */ WEEKLY,
    /** 版本复盘沿用已确认调查，但不会在缺少版本事件时编造因果。 */ VERSION_REVIEW
}
