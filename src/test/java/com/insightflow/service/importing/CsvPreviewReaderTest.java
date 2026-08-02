package com.insightflow.service.importing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 验证 CSV 预览只读取有限行并在返回样例前执行 PII 脱敏。
 */
class CsvPreviewReaderTest {

    /**
     * 首列表头含 BOM 时仍必须能被前端映射，同时样本中的邮箱不得原样返回。
     */
    @Test
    void removesBomAndReturnsSanitizedLimitedSamples() {
        CsvPreviewReader reader = new CsvPreviewReader(new PiiSanitizer(), 1);
        String csv = "\uFEFF用户反馈,提交时间,来源,工单号\n联系 a@example.com,2026-07-19T00:00:00Z,客服,T-1\n第二条,2026-07-19T01:00:00Z,客服,T-2\n";

        CsvPreviewReader.CsvPreview preview = reader.preview(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(preview.headers()).containsExactly("用户反馈", "提交时间", "来源", "工单号");
        assertThat(preview.samples()).hasSize(1);
        assertThat(preview.samples().get(0).get("用户反馈")).isEqualTo("联系 [EMAIL]");
    }
}
