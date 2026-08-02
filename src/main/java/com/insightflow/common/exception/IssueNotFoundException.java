package com.insightflow.common.exception;

/**
 * 表示 API 边界传入的主题不存在。
 *
 * <p>异常本身不携带数据库内部 id，统一由 REST 异常处理器转换为安全的 404 响应。</p>
 */
public class IssueNotFoundException extends RuntimeException {

    /**
     * 保留请求中的主题键，便于调用方定位错误而不暴露内部实现。
     */
    public IssueNotFoundException(String canonicalKey) {
        super("Issue not found: " + canonicalKey);
    }
}
