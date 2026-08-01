package com.insightflow.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.insightflow.entity.Alert;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.repository.AlertRepository;
import com.insightflow.repository.IssueCatalogRepository;
import com.insightflow.repository.RiskPrioritySnapshotRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** 告警优先级必须在触发时冻结，避免后续规则变化改写当时的运营决策依据。 */
@ExtendWith(MockitoExtension.class)
class RiskPrioritySnapshotServiceTest {

    @Mock private AlertRepository alertRepository;
    @Mock private IssueCatalogRepository issueCatalogRepository;
    @Mock private RiskPrioritySnapshotRepository snapshotRepository;
    @Mock private RiskPriorityService priorityService;
    @InjectMocks private RiskPrioritySnapshotService service;

    /** 登录类告警应采用高风险主题权重并保存 P0 快照。 */
    @Test
    void freezesPriorityForNewAlert() {
        Alert alert = Alert.active(7L, 8L, 9L, OffsetDateTime.now(), 30, 2, 1, 8, 5, "{}");
        ReflectionTestUtils.setField(alert, "id", 10L);
        IssueCatalog issue = IssueCatalog.create(7L, "login_failure", "登录失败");
        when(alertRepository.findById(10L)).thenReturn(Optional.of(alert));
        when(issueCatalogRepository.findById(8L)).thenReturn(Optional.of(issue));
        when(snapshotRepository.findByWorkspaceIdAndAlertId(7L, 10L)).thenReturn(Optional.empty());
        when(priorityService.score(alert, 20, 0)).thenReturn(new RiskPriority(RiskLevel.P0, 85, java.util.List.of("异常强度高")));
        when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RiskPrioritySnapshot result = service.recordForAlert(7L, 10L);

        assertThat(result.getLevel()).isEqualTo(RiskLevel.P0);
        assertThat(result.getScore()).isEqualTo(85);
        assertThat(result.getReasons()).contains("异常强度高");
    }
}
