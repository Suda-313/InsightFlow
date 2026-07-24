package com.insightflow.agent.investigation;

import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 基于明确中文业务词的保守意图识别器。
 *
 * <p>P2 首版刻意不调用第二个模型分类意图：规则可解释、无额外耗时和 Token，且未知问题会退回
 * 最小查询。后续若替换为模型分类器，必须保持 {@link #detect(String)} 契约并纳入 P1 金标评测。</p>
 */
@Component
public class InvestigationIntentDetector {

    /**
     * 按业务风险从强到弱识别意图；报告和版本比较优先于一般“变化”词，避免错把专项问题降级为趋势查询。
     */
    public InvestigationIntent detect(String question) {
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "周报", "日报", "专题报告", "生成报告", "报告")) {
            return InvestigationIntent.REPORT_GENERATION;
        }
        if (containsAny(normalized, "版本", "上线", "更新前后", "版本前后")) {
            return InvestigationIntent.VERSION_COMPARISON;
        }
        if (containsAny(normalized, "环比", "同比", "对比", "上周", "本周", "上一周期")) {
            return InvestigationIntent.PERIOD_COMPARISON;
        }
        if (containsAny(normalized, "暴增", "异常", "告警", "为什么", "原因", "激增")) {
            return InvestigationIntent.ANOMALY_INVESTIGATION;
        }
        if (containsAny(normalized, "趋势", "变化", "走势", "增长", "下降")) {
            return InvestigationIntent.TREND_EXPLANATION;
        }
        return InvestigationIntent.GENERAL_INQUIRY;
    }

    /** 一个关键词命中即可；关键词列表由代码固定，不接受用户提供的动态模式或正则表达式。 */
    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
