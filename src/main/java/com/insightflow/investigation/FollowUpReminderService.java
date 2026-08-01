package com.insightflow.investigation;

import com.insightflow.repository.InvestigationCaseRepository;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 最小站内响应提醒：超过 SLA 仍无人开始跟进的卡片被标记为需提醒。
 * 它不分派负责人、不发送外部消息，也不改变 Agent 调查和处置提案的状态。
 */
@Service
public class FollowUpReminderService {
    /** 仓储查询将响应状态和时间窗口下推数据库，避免扫描全部调查历史。 */
    private final InvestigationCaseRepository investigationCaseRepository;
    /** 时限由部署配置提供，默认 30 分钟，首期只作为运营提醒而非考核指标。 */
    private final int overdueMinutes;

    public FollowUpReminderService(InvestigationCaseRepository investigationCaseRepository,
                                   @Value("${insightflow.investigation.follow-up-reminder-minutes:30}") int overdueMinutes) {
        this.investigationCaseRepository = investigationCaseRepository;
        this.overdueMinutes = overdueMinutes;
    }

    /** 周期扫描入口保持短事务，具体卡片状态由实体方法约束。 */
    @Scheduled(fixedDelayString = "${insightflow.investigation.follow-up-reminder-delay-ms:60000}")
    public void scheduledMarkOverdue() {
        markOverdue(OffsetDateTime.now());
    }

    /** 公开给测试的单次执行入口，确保超时边界可确定复现。 */
    @Transactional
    public void markOverdue(OffsetDateTime now) {
        investigationCaseRepository.findByFollowUpStatusAndFollowUpReminderAtIsNullAndCreatedAtBefore(
                        "awaiting_follow_up", now.minusMinutes(overdueMinutes))
                .forEach(investigation -> {
                    investigation.markFollowUpReminder();
                    investigationCaseRepository.save(investigation);
                });
    }
}
