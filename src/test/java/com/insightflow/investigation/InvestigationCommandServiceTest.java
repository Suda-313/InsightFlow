package com.insightflow.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.insightflow.entity.Alert;
import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.InvestigationCase;
import com.insightflow.entity.Workspace;
import com.insightflow.investigation.window.InvestigationWindowPolicy;
import com.insightflow.investigation.window.InvestigationWindowResolver;
import com.insightflow.repository.AlertRepository;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.InvestigationCaseRepository;
import com.insightflow.security.WorkspaceAccessService;
import java.time.OffsetDateTime;
import java.lang.reflect.Field;
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

    /** 该测试防止任务先入队、计划后冻结，避免 Worker 在重试或快速调度时读取半成品窗口。 */
    @Test
    void freezesDefaultPlanBeforeCreatingAsyncTask() throws Exception {
        Alert alert = Alert.active(7L, 3L, 5L, OffsetDateTime.parse("2026-08-08T00:00:00Z"), 10, 2, 1, 3, 5, "{}");
        setId(alert, 9L);
        AsyncTask task = org.mockito.Mockito.mock(AsyncTask.class);
        when(task.getId()).thenReturn(11L);
        when(caseRepository.findByWorkspaceIdAndAlertId(7L, 9L)).thenReturn(Optional.empty());
        when(taskRepository.findByWorkspaceIdAndTaskTypeAndIdempotencyKey(7L, "investigation", "investigation:" + alert.getPublicId()))
                .thenReturn(Optional.empty());
        when(caseRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskRepository.saveAndFlush(any())).thenReturn(task);
        InvestigationCommandService commandService = new InvestigationCommandService(
                accessService, alertRepository, caseRepository, taskRepository,
                new InvestigationPlanFreezer(new InvestigationWindowPolicy(), new InvestigationWindowResolver(), new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()));

        InvestigationCase investigation = commandService.enqueueForAlert(alert);

        assertThat(investigation.getPlanJson()).contains("\"finalWindowType\":\"WEEKLY\"")
                .contains("2026-08-08T00:00Z");
        assertThat(investigation.getAsyncTaskId()).isEqualTo(11L);
    }

    private static void setId(Alert alert, long id) throws Exception {
        Field field = Alert.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(alert, id);
    }
}
