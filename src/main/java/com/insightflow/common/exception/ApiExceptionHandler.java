package com.insightflow.common.exception;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.insightflow.security.AuthenticationRequiredException;
import com.insightflow.security.InvalidJwtTokenException;
import com.insightflow.security.MembershipConflictException;
import com.insightflow.security.WorkspaceAccessDeniedException;
import com.insightflow.storage.RawObjectStorageException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 将领域异常收敛为稳定的 HTTP 错误契约。
 *
 * <p>响应中只包含调用方可处理的错误码、追踪标识和字段错误，不输出堆栈、SQL 或内部主键。</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * 未预期异常仅在服务端按 trace_id 记录，客户端继续收到不含内部细节的稳定错误契约。
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * Workspace 不存在不是服务器异常，应返回可预测的 404 与业务错误码。
     */
    @ExceptionHandler(WorkspaceNotFoundException.class)
    public ResponseEntity<ErrorEnvelope> handleNotFound(WorkspaceNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage(), List.of());
    }

    /**
     * 主题不存在时返回 404，复用统一的资源找不到错误码。
     */
    @ExceptionHandler(IssueNotFoundException.class)
    public ResponseEntity<ErrorEnvelope> handleIssueNotFound(IssueNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage(), List.of());
    }

    /**
     * 会话不存在与会话不属于当前工作区统一返回 404，避免利用差异响应探测其他工作区的对话。
     */
    @ExceptionHandler(ChatSessionNotFoundException.class)
    public ResponseEntity<ErrorEnvelope> handleChatSessionNotFound(ChatSessionNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "对话会话不存在。", List.of());
    }

    /** AgentRun Trace 不存在或不属于当前工作区时统一返回 404，阻断跨工作区运行记录探测。 */
    @ExceptionHandler(AgentRunNotFoundException.class)
    public ResponseEntity<ErrorEnvelope> handleAgentRunNotFound(AgentRunNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Agent 运行记录不存在。", List.of());
    }

    /**
     * 文件不存在或不属于当前 Workspace 时复用 404 语义，不区分两种情况以避免越权探测。
     */
    @ExceptionHandler(ImportFileNotFoundException.class)
    public ResponseEntity<ErrorEnvelope> handleImportFileNotFound(ImportFileNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "导入文件不存在。", List.of());
    }

    /**
     * 映射、状态和 CSV 结构问题属于调用方可修正的 422，不泄漏任何上传行内容。
     */
    @ExceptionHandler(ImportValidationException.class)
    public ResponseEntity<ErrorEnvelope> handleImportValidation(ImportValidationException exception) {
        List<FieldError> fieldErrors = exception.getFieldErrors().stream()
                .map(error -> new FieldError(error.field(), error.reason()))
                .toList();
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", exception.getMessage(), fieldErrors);
    }

    /**
     * 映射 JSON 缺失、格式错误或字段类型不匹配同样属于调用方可修正的输入校验，不应伪装为服务器故障。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorEnvelope> handleUnreadableRequest(HttpMessageNotReadableException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "请求 JSON 格式或字段类型不合法。", List.of());
    }

    /**
     * Spring multipart 拦截的大文件在进入业务层前返回 413，避免模糊的 500 错误。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorEnvelope> handleMaxUploadSize(MaxUploadSizeExceededException exception) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "上传文件超过当前大小限制。", List.of());
    }

    /**
     * MinIO 不可用时返回依赖故障，不将 endpoint、bucket 或 SDK 异常输出给客户端。
     */
    @ExceptionHandler(RawObjectStorageException.class)
    public ResponseEntity<ErrorEnvelope> handleObjectStorageUnavailable(RawObjectStorageException exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "原始文件存储暂不可用，请稍后重试。", List.of());
    }

    /**
     * 未预期异常只返回固定 500 契约；详细异常留在服务器日志，避免把存储或 SQL 细节泄漏给调用方。
     */
    /**
     * 未登录、Token 无效或账号被禁用统一返回 401；不区分密码、账户或签名的具体失败原因。
     */
    @ExceptionHandler({AuthenticationRequiredException.class, InvalidJwtTokenException.class})
    public ResponseEntity<ErrorEnvelope> handleAuthentication(RuntimeException exception) {
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "需要有效的登录凭证", List.of());
    }

    /**
     * 组织归属、Workspace 范围或角色不满足时统一返回 403，避免借错误差异探测成员关系。
     */
    @ExceptionHandler(WorkspaceAccessDeniedException.class)
    public ResponseEntity<ErrorEnvelope> handleAccessDenied(WorkspaceAccessDeniedException exception) {
        return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "无权访问当前工作区资源", List.of());
    }

    /**
     * 重复登录名是调用方可修正的资源冲突，使用 409 而不是把数据库唯一约束异常暴露为 500。
     */
    @ExceptionHandler(MembershipConflictException.class)
    public ResponseEntity<ErrorEnvelope> handleMembershipConflict(MembershipConflictException exception) {
        return error(HttpStatus.CONFLICT, "MEMBERSHIP_CONFLICT", exception.getMessage(), List.of());
    }

    /**
     * 服务层对边界参数的防御性校验统一返回 422，保持与请求体校验相同的客户端可修复语义。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorEnvelope> handleIllegalArgument(IllegalArgumentException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", exception.getMessage(), List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelope> handleUnexpected(Exception exception) {
        String traceId = UUID.randomUUID().toString();
        LOGGER.error("Unhandled API exception, trace_id={}", traceId, exception);
        ErrorBody body = new ErrorBody("INTERNAL_ERROR", "服务暂时不可用，请稍后重试。", traceId, List.of());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorEnvelope(body));
    }

    /**
     * 所有业务错误都创建同一结构的响应体；trace_id 只用于支持排障，不包含用户或文件内容。
     */
    private ResponseEntity<ErrorEnvelope> error(
            HttpStatus status, String code, String message, List<FieldError> fieldErrors) {
        ErrorBody body = new ErrorBody(code, message, UUID.randomUUID().toString(), fieldErrors);
        return ResponseEntity.status(status).body(new ErrorEnvelope(body));
    }

    /**
     * 错误外层固定为 error 键，使客户端可以安全区分正常业务响应和失败响应。
     */
    public record ErrorEnvelope(ErrorBody error) {
    }

    /**
     * 公开错误体严格使用 API 文档的 snake_case 字段名，不附带时间戳或框架异常类型。
     */
    public record ErrorBody(
            String code,
            String message,
            @JsonProperty("trace_id") String traceId,
            @JsonProperty("field_errors") List<FieldError> fieldErrors) {
    }

    /**
     * 映射校验专用字段错误，只说明规范字段与原因，不回显 CSV 的原始单元格内容。
     */
    public record FieldError(String field, String reason) {
    }
}
