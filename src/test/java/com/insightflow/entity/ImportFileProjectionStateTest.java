package com.insightflow.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 固定导入文件与自动看板投影之间的状态边界，避免报告重跑影响文件投影事实。
 */
class ImportFileProjectionStateTest {

    /**
     * 成功导入后的文件先等待投影，再由自动任务写入最终投影结果。
     */
    @Test
    void tracksAutomaticProjectionWithoutChangingImportStatus() {
        ImportFile file = ImportFile.uploaded(1L, 2L, "workspace/file.csv", "file.csv", "text/csv", 12L, "a".repeat(64));

        assertThat(file.getProjectionStatus()).isEqualTo("pending");

        file.markProjecting();
        assertThat(file.getProjectionStatus()).isEqualTo("projecting");

        file.markProjected();
        assertThat(file.getProjectionStatus()).isEqualTo("projected");
        assertThat(file.getStatus()).isEqualTo("uploaded");
    }

    /**
     * 晚到历史数据不能静默改写当前基线，文件必须显式等待未来受控重建。
     */
    @Test
    void marksLateDataAsRebuildRequired() {
        ImportFile file = ImportFile.uploaded(1L, 2L, "workspace/late.csv", "late.csv", "text/csv", 12L, "b".repeat(64));

        file.markRebuildRequired();

        assertThat(file.getProjectionStatus()).isEqualTo("rebuild_required");
    }
}
