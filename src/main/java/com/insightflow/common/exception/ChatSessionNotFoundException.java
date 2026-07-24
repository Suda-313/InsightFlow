package com.insightflow.common.exception;

import java.util.UUID;

/**
 * 表示会话不存在，或不属于当前工作区。
 *
 * <p>两种情况统一为 404，避免调用方利用响应差异探测其他工作区的会话 UUID。</p>
 */
public class ChatSessionNotFoundException extends RuntimeException {

    /** 仅记录客户端给出的公共标识，不暴露内部主键或归属工作区。 */
    public ChatSessionNotFoundException(UUID publicId) {
        super("Chat session not found: " + publicId);
    }
}
