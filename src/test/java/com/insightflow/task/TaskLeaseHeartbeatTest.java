package com.insightflow.task;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.repository.AsyncTaskRepository;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies that renewal uses the claim's owner and execution version as a single fence. */
class TaskLeaseHeartbeatTest {

    @Test
    void renewsOnlyTheRegisteredExecutionVersion() {
        AsyncTaskRepository repository = mock(AsyncTaskRepository.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        when(repository.renewLeaseIfOwned(any(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(1);

        TaskLeaseHeartbeat heartbeat = new TaskLeaseHeartbeat(repository, transactionTemplate, 120, 3600);
        UUID taskId = UUID.randomUUID();
        heartbeat.register(taskId, "worker-a", 3, Duration.ofMinutes(5));

        heartbeat.renewAll();

        verify(repository).renewLeaseIfOwned(taskId, "worker-a", 3, 120);
        heartbeat.unregister(taskId, 3);
        heartbeat.stop();
    }

    @Test
    void oldExecutionCannotUnregisterReclaimedTaskHeartbeat() {
        AsyncTaskRepository repository = mock(AsyncTaskRepository.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        when(repository.renewLeaseIfOwned(any(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(1);

        TaskLeaseHeartbeat heartbeat = new TaskLeaseHeartbeat(repository, transactionTemplate, 120, 3600);
        UUID taskId = UUID.randomUUID();
        heartbeat.register(taskId, "worker-a", 4, Duration.ofMinutes(5));
        heartbeat.register(taskId, "worker-a", 5, Duration.ofMinutes(5));

        heartbeat.unregister(taskId, 4);
        heartbeat.renewAll();

        verify(repository).renewLeaseIfOwned(taskId, "worker-a", 5, 120);
        heartbeat.unregister(taskId, 5);
        heartbeat.stop();
    }
}
