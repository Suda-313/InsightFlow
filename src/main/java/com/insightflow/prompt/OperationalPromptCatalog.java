package com.insightflow.prompt;

import org.springframework.stereotype.Component;

/**
 * 非聊天运营 Agent 的集中式 Prompt 目录。
 *
 * <p>分类、情感、风险与报告的系统提示词必须从本类取得：修改正文时同步提升对应版本，
 * 使日志、评测和后续 AgentRun 能准确定位一次模型行为由哪个提示词产生。</p>
 */
@Component
public class OperationalPromptCatalog {

    /** 工单分类只允许已有规范类别，防止模型扩展不可落库的 canonical key。 */
    private static final VersionedPrompt CLASSIFICATION = new VersionedPrompt("classification:v1", """
            你是游戏客服工单分类助手。根据工单文本，判断它属于哪个问题类别。
            - 只能从已知类别中选择：login_failure(登录失败), payment_recharge(充值异常),
              item_loss(道具丢失), account_recovery(账号找回), bug_gameplay(玩法bug),
              bug_network(网络问题), violation_report(违规举报), suggestion(建议反馈)
            - 如果确实不属于任何类别，返回 canonical_key="unclassified"
            - confidence 表示你的确信度（0.0-1.0）
            - reasoning 用一句话解释分类理由
            - keywords 提取3-5个关键词
            """);

    /** 情感和紧急程度均受枚举约束，保证下游投影可稳定聚合。 */
    private static final VersionedPrompt SENTIMENT = new VersionedPrompt("sentiment:v1", """
            你是游戏客服情感分析助手。判断玩家情绪和紧急程度。
            - sentiment: positive(满意), neutral(中性), negative(不满), angry(愤怒)
            - urgency: low(低), medium(中), high(高), critical(紧急)
            - keywords: 提取情感关键词
            """);

    /** 风险输出仅用于辅助人工研判，不会自动触发外部策略变更。 */
    private static final VersionedPrompt RISK = new VersionedPrompt("risk:v1", """
            你是游戏运营风险分析助手。判断该反馈是否存在公关危机风险。
            - risk_level: none(无), low(低), medium(中), high(高)
            - crisis_potential: 0.0-1.0 危机潜势
            - risk_reasons: 风险原因列表
            """);

    /** 报告 Agent 的系统角色与用户数据分离，避免聚合数据覆盖固定写作约束。 */
    private static final VersionedPrompt REPORT = new VersionedPrompt(
            "report:v1", "你是游戏客服数据分析助手，请生成运营周报。");

    /** 返回分类 Agent 使用的版本化提示词。 */
    public VersionedPrompt classification() {
        return CLASSIFICATION;
    }

    /** 返回情感 Agent 使用的版本化提示词。 */
    public VersionedPrompt sentiment() {
        return SENTIMENT;
    }

    /** 返回风险 Agent 使用的版本化提示词。 */
    public VersionedPrompt risk() {
        return RISK;
    }

    /** 返回报告 Agent 使用的版本化提示词。 */
    public VersionedPrompt report() {
        return REPORT;
    }

    /**
     * 报告数据提示词的固定骨架也集中在目录内；仅聚合数值与主题分布由调用方提供，
     * 不允许 ReportAgent 在本地复制另一份写作要求。
     */
    public String renderReportUserPrompt(long actualTicketCount, Object issueMentions) {
        return "根据以下聚合数据生成一份运营周报：\n"
                + "- 实际总工单数：" + actualTicketCount + "\n"
                + "- 主题分布：" + issueMentions + "\n\n"
                + "请生成一份包含执行摘要、要点、建议和风险提示的报告。";
    }

    /**
     * 版本号和系统提示词构成不可分割的评测维度；正文改动而不升版本会使历史指标失去可比性。
     */
    public record VersionedPrompt(String version, String systemPrompt) {
    }
}
