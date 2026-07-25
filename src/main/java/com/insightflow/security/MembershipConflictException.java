package com.insightflow.security;

/**
 * 成员创建或授权与现有关系冲突时的受控业务异常。
 *
 * <p>异常不携带账户内部主键、密码哈希或数据库约束名称，Controller 可将其稳定映射为 409，调用方据此改用已有账户的后续授权流程。</p>
 */
public class MembershipConflictException extends RuntimeException {

    /**
     * 统一使用不暴露内部实现的业务提示，避免把唯一约束名称泄露给 API 调用方。
     */
    public MembershipConflictException(String message) {
        super(message);
    }
}
