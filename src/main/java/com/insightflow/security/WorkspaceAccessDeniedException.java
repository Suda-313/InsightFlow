package com.insightflow.security;

import java.util.UUID;

/**
 * 当前账号不具有目标工作区读取或写入权限时的受控异常。
 *
 * <p>异常只保留外部 Workspace UUID，不能携带成员内部主键、角色查询 SQL 或其他用户信息。</p>
 */
public class WorkspaceAccessDeniedException extends RuntimeException {

    /** 统一消息防止调用方推测是没有成员关系还是角色不足。 */
    public WorkspaceAccessDeniedException(UUID workspacePublicId) {
        super("无权访问工作区: " + workspacePublicId);
    }
}
