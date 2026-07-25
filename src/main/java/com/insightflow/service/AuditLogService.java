package com.insightflow.service;

import com.insightflow.entity.AuditLog;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.AuditLogRepository;
import com.insightflow.security.CurrentUser;
import com.insightflow.security.WorkspaceAccessService;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 受控写入审计事实的唯一服务入口。
 *
 * <p>所有 P4 命令服务通过本服务记录结果，不能直接操作 {@link AuditLogRepository}。服务在写入前重新校验 Workspace 访问、绑定当前账户，并拒绝原始 JSON；这避免调用方伪造操作者或把敏感输入写入审计表。</p>
 */
@Service
@Transactional
public class AuditLogService {

    /** 仅允许稳定、机器可聚合的点分动作名，禁止直接存储自由文本。 */
    private static final Pattern ACTION_PATTERN = Pattern.compile("[a-z][a-z0-9._-]{2,99}");

    /** 对常见敏感键的值进行替换，作为摘要防御的最后一道保护。 */
    private static final Pattern SECRET_VALUE_PATTERN = Pattern.compile("(?i)(password|token|secret)=[^,\\s]+");

    /** 获取已授权的 Workspace 内部键，禁止相信调用方提交的内部 ID。 */
    private final WorkspaceAccessService accessService;

    /** 审计操作者始终取自安全上下文，不能从 Controller 请求体传入。 */
    private final CurrentUser currentUser;

    /** 审计持久化只保存不可变事实，不承担业务状态改变。 */
    private final AuditLogRepository auditLogRepository;

    /** 构造器显式隔离授权、身份和持久化职责，便于命令服务统一复用。 */
    public AuditLogService(
            WorkspaceAccessService accessService, CurrentUser currentUser, AuditLogRepository auditLogRepository) {
        this.accessService = accessService;
        this.currentUser = currentUser;
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * 记录当前认证账户在当前 Workspace 内完成的一项受控动作。
     */
    public AuditLog record(UUID workspacePublicId, String action, UUID targetPublicId, String summary) {
        Workspace workspace = accessService.requireRead(workspacePublicId);
        return auditLogRepository.save(AuditLog.record(
                workspace.getId(), currentUser.requirePublicId(), requireAction(action), requireTarget(targetPublicId), sanitizeSummary(summary)));
    }

    /**
     * 动作名是报告和权限策略的稳定索引，不能接收空值、过长值或用户自由描述。
     */
    private String requireAction(String action) {
        if (action == null || !ACTION_PATTERN.matcher(action).matches()) {
            throw new IllegalArgumentException("审计动作格式不合法");
        }
        return action;
    }

    /**
     * 没有目标 UUID 的事件无法被调查、提案或报告证据可靠追溯，因此拒绝写入。
     */
    private UUID requireTarget(UUID targetPublicId) {
        if (targetPublicId == null) {
            throw new IllegalArgumentException("审计目标不能为空");
        }
        return targetPublicId;
    }

    /**
     * 摘要不能是 JSON 或空白，并对敏感键值执行脱敏与长度限制；详细输入只保留在调用链内，不进入审计表。
     */
    private String sanitizeSummary(String summary) {
        if (summary == null || summary.isBlank() || summary.indexOf('{') >= 0 || summary.indexOf('}') >= 0) {
            throw new IllegalArgumentException("审计摘要必须是受控文本，不能包含原始命令体");
        }
        String sanitized = SECRET_VALUE_PATTERN.matcher(summary.trim()).replaceAll("$1=[REDACTED]");
        if (sanitized.length() > 500) {
            throw new IllegalArgumentException("审计摘要不能超过 500 个字符");
        }
        return sanitized;
    }
}
