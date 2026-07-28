package com.insightflow.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.insightflow.entity.Alert;
import com.insightflow.entity.InvestigationCase;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.AlertRepository;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.InvestigationCaseRepository;
import com.insightflow.security.WorkspaceAccessService;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 调查命令幂等性回归测试；测试文件仅在本地保留，不进入本次提交。 */
@ExtendWith(MockitoExtension.class)
class InvestigationCommandServiceTest {

    @Mock private WorkspaceAccessService accessService;
    @Mock private AlertRepository alertRepository;
    @Mock private InvestigationCaseRepository caseRepository;
    @Mock private AsyncTaskRepository taskRepository;
    @InjectMocks private InvestigationCommandService service;

    /** 同一告警被重复提交时必须返回同一调查卡片，而不是创建第二个异步任务。 */
    @Test
    void createsOnlyOneInvestigationTaskForSameAlert() {
        UUID workspaceId = UUID.randomUUID();
        UUID alertId = UUID.randomUUID();
        Workspace workspace = org.mockito.Mockito.mock(Workspace.class);
        Alert alert = Alert.active(7L, 3L, 5L, OffsetDateTime.now(), 10, 2, 1, 3, 5, "{}");
        InvestigationCase existing = InvestigationCase.queued(7L, 9L);
        when(workspace.getId()).thenReturn(7L);
        when(accessService.requireRead(workspaceId)).thenReturn(workspace);
        when(alertRepository.findByWorkspaceIdAndPublicId(7L, alertId)).thenReturn(Optional.of(alert));
        when(caseRepository.findByWorkspaceIdAndAlertId(7L, alert.getId())).thenReturn(Optional.of(existing));

        InvestigationCase first = service.enqueue(workspaceId, alertId);
        InvestigationCase second = service.enqueue(workspaceId, alertId);

        assertThat(second.getPublicId()).isEqualTo(first.getPublicId());
    }
}
