package com.insightflow.service.importing;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * CSV 预览、数据库事件和后续证据链共用的基础 PII 脱敏器。
 *
 * <p>V1 先覆盖导入反馈中最常见且风险最高的邮箱和中国大陆手机号。该组件只做替换、不会尝试
 * 还原或保存原始值；新增规则必须同步补充 Harness 和误伤样本。</p>
 */
@Component
public class PiiSanitizer {

    /**
     * 邮箱边界规则避免把普通文本中的 @ 符号误当成完整邮箱。
     */
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");

    /**
     * 覆盖带或不带国家区号的大陆手机号，不匹配更长数字串中的局部片段。
     */
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+?86[-\\s]?)?1[3-9]\\d{9}(?!\\d)");

    /**
     * 对可展示、可分析的文本应用固定顺序脱敏；空值保持为空，避免伪造反馈内容。
     */
    public String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String withoutEmail = EMAIL.matcher(value).replaceAll("[EMAIL]");
        return PHONE.matcher(withoutEmail).replaceAll("[PHONE]");
    }
}
