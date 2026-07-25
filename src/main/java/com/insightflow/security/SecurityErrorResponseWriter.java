package com.insightflow.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.common.exception.ApiExceptionHandler;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Spring Security 过滤器链中认证失败的统一 JSON 响应写入器。
 *
 * <p>过滤器链发生的 401/403 不会进入 {@code @RestControllerAdvice}，因此在此复用同一错误外壳，确保前端可以始终按 error.code 和 trace_id 处理失败。</p>
 */
@Component
public class SecurityErrorResponseWriter {

    /** 使用应用统一的 Jackson 配置，避免手工拼接 JSON 造成转义或字段命名不一致。 */
    private final ObjectMapper objectMapper;

    /** 构造器仅接收序列化依赖，状态码和消息由调用方按安全语义明确提供。 */
    public SecurityErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 写入不含认证细节的稳定错误契约；写失败时由容器按已提交响应处理，不再覆盖原始安全响应。
     */
    public void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiExceptionHandler.ErrorBody body = new ApiExceptionHandler.ErrorBody(code, message, UUID.randomUUID().toString(), List.of());
        objectMapper.writeValue(response.getWriter(), new ApiExceptionHandler.ErrorEnvelope(body));
    }
}
