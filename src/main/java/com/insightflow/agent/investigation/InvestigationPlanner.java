package com.insightflow.agent.investigation;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 将受控意图映射为最少必要的只读 Tool 集合。
 *
 * <p>规划器不读取数据、不执行模型调用，也不允许调用方传入 Tool 名称；这种分层使权限、成本与
 * 证据范围可以在模型调用前确定。每个意图只保留回答该类问题所需的最小集合。</p>
 */
@Component
public class InvestigationPlanner {

    /** 意图识别器可独立替换，但返回值必须仍受枚举和本规划器白名单约束。 */
    private final InvestigationIntentDetector intentDetector;

    /** 通过构造器注入便于用纯单元测试锁定意图到 Tool 的映射。 */
    public InvestigationPlanner(InvestigationIntentDetector intentDetector) {
        this.intentDetector = intentDetector;
    }

    /**
     * 为用户问题创建不可变计划；未知问题只读取主题分布，避免默认获取告警或样本文本。
     */
    public InvestigationPlan plan(String question) {
        InvestigationIntent intent = intentDetector.detect(question);
        return new InvestigationPlan(intent, toolsFor(intent));
    }

    /**
     * 将各类问题映射为固定、最小的 Tool 序列。版本比较附带可用性检查，而不是凭空假设版本数据存在。
     */
    private List<InvestigationToolType> toolsFor(InvestigationIntent intent) {
        return switch (intent) {
            case TREND_EXPLANATION -> List.of(InvestigationToolType.ISSUE_TREND);
            case ANOMALY_INVESTIGATION -> List.of(
                    InvestigationToolType.ISSUE_TREND,
                    InvestigationToolType.ALERT_HISTORY,
                    InvestigationToolType.SAMPLE_FEEDBACK);
            case PERIOD_COMPARISON -> List.of(
                    InvestigationToolType.ISSUE_TREND,
                    InvestigationToolType.PERIOD_COMPARISON);
            case VERSION_COMPARISON -> List.of(
                    InvestigationToolType.ISSUE_TREND,
                    InvestigationToolType.PERIOD_COMPARISON,
                    InvestigationToolType.DATA_AVAILABILITY);
            case REPORT_GENERATION -> List.of(
                    InvestigationToolType.EXPRESSION_DISTRIBUTION,
                    InvestigationToolType.TOPIC_DISTRIBUTION,
                    InvestigationToolType.ALERT_HISTORY,
                    InvestigationToolType.ISSUE_TREND);
            case EXPRESSION_INQUIRY -> List.of(
                    InvestigationToolType.EXPRESSION_DISTRIBUTION,
                    InvestigationToolType.EXPRESSION_TOPIC_DRILLDOWN,
                    InvestigationToolType.EXPRESSION_TOPIC_SAMPLES);
            case GENERAL_INQUIRY -> List.of(InvestigationToolType.TOPIC_DISTRIBUTION);
        };
    }
}
