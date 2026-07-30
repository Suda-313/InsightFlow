package com.insightflow.config;

import java.util.Locale;
import org.springframework.web.client.HttpStatusCodeException;

/**
 * 判断聊天模型失败是否应切换到备用模型。
 *
 * <p>仅识别额度、限流和短暂不可用等可恢复错误；鉴权失败、模型不存在或参数错误仍应直接失败，
 * 避免把配置问题误当成额度问题反复重试。</p>
 */
final class ChatModelQuotaFailureDetector {

    private ChatModelQuotaFailureDetector() {}

    static boolean shouldTryFallback(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof HttpStatusCodeException http) {
                int status = http.getStatusCode().value();
                if (status == 429 || status == 503) {
                    return true;
                }
            }
            String message = current.getMessage();
            if (message != null && matchesQuotaOrRateLimit(message)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean matchesQuotaOrRateLimit(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("quota")
                || lower.contains("insufficient")
                || lower.contains("rate limit")
                || lower.contains("ratelimit")
                || lower.contains("throttl")
                || lower.contains("limit exceeded")
                || lower.contains("exceeded your current quota")
                || lower.contains("余额不足")
                || lower.contains("额度");
    }
}
