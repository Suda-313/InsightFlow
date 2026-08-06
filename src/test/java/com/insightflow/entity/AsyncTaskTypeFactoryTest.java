package com.insightflow.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 验证看板投影和报告复用同一租约任务模型，但不伪造 CSV 文件关联。
 */
class AsyncTaskTypeFactoryTest {

    /** 每次领取必须生成新的执行版本，旧 Worker 不能沿用旧版本继续写任务终态。 */
    @Test
    void claimCreatesExecutionVersionThatGuardsLeaseOwnership() {
        AsyncTask task = AsyncTask.queuedReport(7L, "report:request:lease", "{}");
        task.claim("worker-a", java.time.OffsetDateTime.now().plusMinutes(2));

        assertThat(task.isLeaseOwnedBy("worker-a", 1)).isTrue();
        assertThat(task.isLeaseOwnedBy("worker-a", 2)).isFalse();
    }

    /**
     * 自动投影是 Workspace 级命令，任务仍从 queued 状态进入既有租约流程。
     */
    @Test
    void createsQueuedProjectionWithoutAnImportFileReference() {
        AsyncTask task = AsyncTask.queuedProjection(7L, "projection:file:9", "{\"file_ids\":[\"x\"]}");

        assertThat(task.getTaskType()).isEqualTo("projection");
        assertThat(task.getStatus()).isEqualTo("queued");
        assertThat(task.getImportFileId()).isNull();
        assertThat(task.getWorkspaceId()).isEqualTo(7L);
    }

    /**
     * 报告是独立只读命令，不得因创建报告而回到导入文件状态机。
     */
    @Test
    void createsQueuedReportWithoutAnImportFileReference() {
        AsyncTask task = AsyncTask.queuedReport(7L, "report:request:11", "{\"file_ids\":[\"x\"]}");

        assertThat(task.getTaskType()).isEqualTo("analysis_report");
        assertThat(task.getStatus()).isEqualTo("queued");
        assertThat(task.getImportFileId()).isNull();
    }
}
