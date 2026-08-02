package com.insightflow.risk;

import static org.mockito.Mockito.verify;

import com.insightflow.investigation.AlertCreatedEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 新告警提交后必须同时启动调查和冻结优先级，两个后置流程相互独立。 */
@ExtendWith(MockitoExtension.class)
class RiskPrioritySnapshotListenerTest {
    @Mock private RiskPrioritySnapshotService snapshotService;
    @InjectMocks private RiskPrioritySnapshotListener listener;

    /** 监听器仅转交内部键，不携带可变实体或原始反馈。 */
    @Test
    void freezesPriorityAfterAlertCommit() {
        listener.onAlertCreated(new AlertCreatedEvent(7L, 8L, UUID.randomUUID()));

        verify(snapshotService).recordForAlert(7L, 8L);
    }
}
