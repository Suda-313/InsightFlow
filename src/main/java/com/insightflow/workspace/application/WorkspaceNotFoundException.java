package com.insightflow.workspace.application;

import java.util.UUID;

/**
 * 表示 API 边界传入的 Workspace 公开标识不存在。
 *
 * <p>异常本身不携带数据库内部 id，统一由 REST 异常处理器转换为安全的 404 响应。</p>
 */
public class WorkspaceNotFoundException extends RuntimeException {

    /**
     * 保留请求中的公开标识，便于调用方定位错误而不暴露内部实现。
     */
    public WorkspaceNotFoundException(UUID publicId) {
        super("Workspace not found: " + publicId);
    }
}
