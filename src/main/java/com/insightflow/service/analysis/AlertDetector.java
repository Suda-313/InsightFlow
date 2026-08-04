package com.insightflow.service.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.Alert;
import com.insightflow.entity.IssueBaselineProfile;
import com.insightflow.repository.AlertRepository;
import com.insightflow.investigation.AlertCreatedEvent;
import com.insightflow.notification.RiskEmailNotificationOutboxService;
import com.insightflow.risk.RiskPrioritySnapshotService;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 告警检测器：根据当日计数、EWMA 基线和冷却期判断是否触发新告警。
 *
 * <p>对齐参考项目 spike_detector.py 的核心逻辑：当今日计数不低于动态
 * 生效阈值，且不在冷却期内时，生成一条 active 告警并持久化。</p>
 */
public class AlertDetector {

    private final AlertRepository alertRepository;
    private final EwmaBaselineService ewmaBaselineService;
    private final int cooldownHours;
    private final int globalAlertThreshold;
    private final double surgeZ;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final RiskPrioritySnapshotService snapshotService;
    private final RiskEmailNotificationOutboxService outboxService;

    public AlertDetector(AlertRepository alertRepository, EwmaBaselineService ewmaBaselineService,
                         int cooldownHours, int globalAlertThreshold, double surgeZ,
                         ObjectMapper objectMapper) {
        this(alertRepository, ewmaBaselineService, cooldownHours, globalAlertThreshold, surgeZ, objectMapper, null, null, null);
    }

    /**
     * 生产构造器额外接收进程内事件发布器，使告警提交后能够异步创建调查；旧构造器保留给纯检测单测，避免测试引入异步副作用。
     */
    public AlertDetector(AlertRepository alertRepository, EwmaBaselineService ewmaBaselineService,
                         int cooldownHours, int globalAlertThreshold, double surgeZ,
                         ObjectMapper objectMapper, ApplicationEventPublisher eventPublisher) {
        this(alertRepository, ewmaBaselineService, cooldownHours, globalAlertThreshold, surgeZ, objectMapper, eventPublisher, null, null);
    }

    public AlertDetector(AlertRepository alertRepository, EwmaBaselineService ewmaBaselineService,
                         int cooldownHours, int globalAlertThreshold, double surgeZ,
                         ObjectMapper objectMapper, ApplicationEventPublisher eventPublisher,
                         RiskPrioritySnapshotService snapshotService, RiskEmailNotificationOutboxService outboxService) {
        this.alertRepository = alertRepository;
        this.ewmaBaselineService = ewmaBaselineService;
        this.cooldownHours = cooldownHours;
        this.globalAlertThreshold = globalAlertThreshold;
        this.surgeZ = surgeZ;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.snapshotService = snapshotService;
        this.outboxService = outboxService;
    }

    /**
     * 根据当前桶计数与基线判断是否需要触发告警。
     *
     * @param workspaceId  一级租户隔离键
     * @param issueId      主题目录内部主键
     * @param projectionId 投影内部主键
     * @param bucketStart  当前桶起始时间
     * @param todayCount   当前桶计数
     * @param baseline     当前主题的 EWMA 基线
     * @return 若触发告警则返回保存后的告警，否则返回 empty
     */
    public Optional<Alert> detect(Long workspaceId, Long issueId, Long projectionId,
                                  OffsetDateTime bucketStart, int todayCount,
                                  IssueBaselineProfile baseline) {
        double std = baseline.baselineStddev();
        double ewma = baseline.getBaselineEwma();
        double zScore = (todayCount - ewma) / Math.max(std, 1.0);
        double surgeZ = ewmaBaselineService.getSurgeZ();
        int effectiveThreshold = Math.max(globalAlertThreshold,
                (int) Math.round(ewma + surgeZ * std));

        if (todayCount < effectiveThreshold) {
            return Optional.empty();
        }

        Optional<Alert> lastAlert = alertRepository
                .findTopByWorkspaceIdAndIssueIdOrderByCreatedAtDesc(workspaceId, issueId);
        if (lastAlert.isPresent()) {
            OffsetDateTime createdAt = lastAlert.get().getCreatedAt();
            if (createdAt != null
                    && ChronoUnit.HOURS.between(createdAt, OffsetDateTime.now()) < cooldownHours) {
                return Optional.empty();
            }
        }

        String evidenceJson = buildEvidenceJson(bucketStart, todayCount, ewma, std,
                zScore, effectiveThreshold, baseline);

        Alert alert = Alert.active(
                workspaceId, issueId, projectionId, bucketStart, todayCount,
                ewma, std, zScore, effectiveThreshold, evidenceJson);
        Alert savedAlert = alertRepository.save(alert);
        AlertCreatedEvent event = new AlertCreatedEvent(savedAlert.getWorkspaceId(), savedAlert.getId(), savedAlert.getPublicId());
        if (snapshotService != null && outboxService != null) {
            snapshotService.recordForAlert(event.workspaceId(), event.alertId());
            outboxService.enqueueIfHighRisk(event);
        }
        if (eventPublisher != null) {
            eventPublisher.publishEvent(event);
        }
        return Optional.of(savedAlert);
    }

    private String buildEvidenceJson(OffsetDateTime bucketStart, int todayCount,
                                     double ewma, double std, double zScore,
                                     int effectiveThreshold, IssueBaselineProfile baseline) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("bucket_start", bucketStart.toString());
        evidence.put("current_count", todayCount);
        evidence.put("baseline_ewma", ewma);
        evidence.put("baseline_std", std);
        evidence.put("z_score", zScore);
        evidence.put("effective_threshold", effectiveThreshold);
        evidence.put("active_buckets", baseline.getActiveBuckets());
        evidence.put("classification", baseline.getClassification());
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize alert evidence", e);
        }
    }
}
