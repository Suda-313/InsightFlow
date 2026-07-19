package com.insightflow.importing.infrastructure;

/**
 * 将对象存储 SDK 的细节收敛为依赖不可用语义。
 *
 * <p>API 层据此返回受控错误而非泄漏 endpoint、bucket、文件路径或 SDK 堆栈。</p>
 */
public class RawObjectStorageException extends RuntimeException {

    /**
     * 保留底层原因给日志与排障使用，但不直接作为 HTTP 响应序列化。
     */
    public RawObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
