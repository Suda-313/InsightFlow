package com.insightflow.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.insightflow.entity.Alert;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.AlertRepository;
import com.insightflow.repository.IssueCatalogRepository;
import com.insightflow.repository.RiskPrioritySnapshotRepository;
import com.insightflow.security.WorkspaceAccessService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** 风险队列必须按已冻结分数读取，不能重新计算或按告警创建时间掩盖高风险事项。 */
@ExtendWith(MockitoExtension.class)
class RiskQueueServiceTest {
    @Mock private WorkspaceAccessService accessService;
    @Mock private RiskPrioritySnapshotRepository snapshotRepository;
    @Mock private AlertRepository alertRepository;
    @Mock private IssueCatalogRepository issueCatalogRepository;
    @InjectMocks private RiskQueueService service;

    /** 返回条目需同时带上业务主题、优先级和解释依据，供前端直接展示。 */
    @Test
    void listsFrozenPriorityItemsForAuthorizedWorkspace() {
        UUID workspacePublicId = UUID.randomUUID();
        Workspace workspace = org.mockito.Mockito.mock(Workspace.class);
        when(workspace.getId()).thenReturn(7L);
        RiskPrioritySnapshot snapshot = RiskPrioritySnapshot.create(7L, 10L,
                new RiskPriority(RiskLevel.P0, 85, List.of("异常强度高")));
        Alert alert = Alert.active(7L, 8L, 9L, OffsetDateTime.now(), 30, 2, 1, 8, 5, "{}");
        IssueCatalog issue = IssueCatalog.create(7L, "login_failure", "登录失败");
        when(accessService.requireRead(workspacePublicId)).thenReturn(workspace);
        when(snapshotRepository.findByWorkspaceIdOrderByScoreDescCreatedAtDesc(7L)).thenReturn(List.of(snapshot));
        when(alertRepository.findById(10L)).thenReturn(java.util.Optional.of(alert));
        when(issueCatalogRepository.findById(8L)).thenReturn(java.util.Optional.of(issue));

        List<RiskQueueService.RiskQueueItem> items = service.list(workspacePublicId);

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.level()).isEqualTo(RiskLevel.P0);
            assertThat(item.score()).isEqualTo(85);
            assertThat(item.issueName()).isEqualTo("登录失败");
            assertThat(item.reasons()).contains("异常强度高");
        });
    }
}
