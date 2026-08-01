package com.insightflow.risk;

import com.insightflow.entity.Alert;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 将已冻结告警事实转换为运营优先级的纯规则服务。
 *
 * <p>评分只依赖 Z-score、反馈规模、受控主题权重和未跟进时长；
 * 不使用无法验证的商业数据，也不允许 Agent 覆盖结果，因此同一输入必然得到同一排序。</p>
 */
@Service
public class RiskPriorityService {

    /** 异常强度贡献上限，防止极端 Z-score 压制其它业务因素。 */
    private static final int SURGE_CAP = 40;
    /** 影响规模贡献上限，当前第一期仅使用已聚合的反馈量。 */
    private static final int VOLUME_CAP = 25;
    /** 未跟进时长最多贡献 15 分，避免旧低风险告警无限累积。 */
    private static final int AGE_CAP = 15;

    /**
     * 计算待办优先级；主题权重须来自受控配置，合法范围为 0 至 20，
     * 未跟进小时数由响应流程传入，首次告警可传 0。
     */
    public RiskPriority score(Alert alert, int issueRiskWeight, int unacknowledgedHours) {
        if (alert == null) {
            throw new IllegalArgumentException("告警不能为空");
        }
        int surgeScore = Math.min(SURGE_CAP, Math.max(0, (int) Math.round(alert.getZScore() * 5)));
        int volumeScore = Math.min(VOLUME_CAP, Math.max(0, alert.getCurrentCount()));
        int issueScore = Math.min(20, Math.max(0, issueRiskWeight));
        int ageScore = Math.min(AGE_CAP, Math.max(0, unacknowledgedHours));
        int total = surgeScore + volumeScore + issueScore + ageScore;

        List<String> reasons = new ArrayList<>();
        reasons.add(surgeScore >= 25 ? "异常强度高" : "异常强度有限");
        reasons.add(volumeScore >= 20 ? "影响规模大" : "影响规模小");
        reasons.add(issueScore >= 15 ? "高风险主题" : "一般风险主题");
        if (ageScore > 0) {
            reasons.add("尚未开始跟进 " + ageScore + " 小时");
        }
        return new RiskPriority(toLevel(total), total, List.copyOf(reasons));
    }

    /** 分级阈值固定在服务中，后续配置化时必须连同策略版本一起冻结。 */
    private RiskLevel toLevel(int total) {
        if (total >= 80) {
            return RiskLevel.P0;
        }
        if (total >= 60) {
            return RiskLevel.P1;
        }
        if (total >= 40) {
            return RiskLevel.P2;
        }
        return RiskLevel.P3;
    }
}
