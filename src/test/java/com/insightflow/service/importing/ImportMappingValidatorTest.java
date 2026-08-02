package com.insightflow.service.importing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.insightflow.common.exception.ImportValidationException;
import com.insightflow.dto.importing.ImportMapping;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 映射门禁的确定性单元测试，阻止无效任务进入异步 Worker。
 */
class ImportMappingValidatorTest {

    /**
     * 必填字段映射到不存在表头时必须在同步 API 阶段失败，而不是耗费后台任务资源。
     */
    @Test
    void rejectsColumnThatDoesNotExistInUploadedHeaders() {
        ImportMappingValidator validator = new ImportMappingValidator();
        ImportMapping mapping = new ImportMapping("反馈", "时间", "来源", "不存在", Map.of("version", "版本"));

        assertThatThrownBy(() -> validator.validate(mapping, List.of("反馈", "时间", "来源", "工单号", "版本")))
                .isInstanceOf(ImportValidationException.class)
                .hasMessage("字段映射校验失败。");
    }

    /**
     * 四个关键语义不能共享同一 CSV 列，避免写入后丢失时间、来源或外部引用事实。
     */
    @Test
    void rejectsReusedRequiredColumn() {
        ImportMappingValidator validator = new ImportMappingValidator();
        ImportMapping mapping = new ImportMapping("反馈", "时间", "来源", "来源", Map.of());

        assertThatThrownBy(() -> validator.validate(mapping, List.of("反馈", "时间", "来源")))
                .isInstanceOf(ImportValidationException.class)
                .hasMessage("字段映射校验失败。");
    }
}
