package com.insightflow.service.importing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 固定验证预览、导入和后续证据共用的基础 PII 脱敏口径。
 */
class PiiSanitizerTest {

    /**
     * 邮箱和大陆手机号必须替换为占位符，不能以部分明文形式落入展示或分析文本。
     */
    @Test
    void sanitizesEmailAndMainlandPhone() {
        PiiSanitizer sanitizer = new PiiSanitizer();

        String sanitized = sanitizer.sanitize("请联系 alice@example.com 或 +86 13812345678 处理问题");

        assertThat(sanitized).isEqualTo("请联系 [EMAIL] 或 [PHONE] 处理问题");
    }

    /**
     * 正常业务反馈不能因脱敏器而被删除或改写，避免影响后续主题和异常统计。
     */
    @Test
    void preservesTextWithoutRecognizedPii() {
        PiiSanitizer sanitizer = new PiiSanitizer();

        assertThat(sanitizer.sanitize("2.4.0 更新后登录闪退")).isEqualTo("2.4.0 更新后登录闪退");
    }
}
