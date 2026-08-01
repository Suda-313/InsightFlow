package com.insightflow.risk;

import java.util.List;

/**
 * 单次告警的确定性排序结果；原因列表是面向运营人员的可复核说明，
 * 不保存模型推理或用户原始反馈，从而可以安全展示在首页风险队列中。
 */
public record RiskPriority(RiskLevel level, int score, List<String> reasons) {
}
