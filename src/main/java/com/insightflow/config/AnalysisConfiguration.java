package com.insightflow.config;

import com.insightflow.service.analysis.DataCellBuilder;
import com.insightflow.service.analysis.ExpressionClassifier;
import com.insightflow.service.analysis.ExpressionRulesLoader;
import com.insightflow.service.analysis.IssueRulesLoader;
import com.insightflow.service.analysis.IssueTextNormalizer;
import com.insightflow.service.analysis.RuleFirstIssueClassifier;
import com.insightflow.service.analysis.TopicPackLoader;
import com.insightflow.service.analysis.TopicPackRegistry;
import com.insightflow.service.analysis.TopicPackTopicLlmSkill;
import com.insightflow.service.analysis.ChatTopicPackTopicLlmSkill;
import com.insightflow.service.analysis.NoOpTopicPackTopicLlmSkill;
import com.insightflow.service.analysis.PackTopicClassifier;
import com.insightflow.service.analysis.TopicLlmSkillProperties;
import com.insightflow.prompt.OperationalPromptCatalog;
import com.insightflow.service.analysis.AlertDetector;
import com.insightflow.service.analysis.EwmaBaselineService;
import com.insightflow.repository.AlertRepository;
import com.insightflow.repository.IssueBaselineProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.prompt.LiteralChatModelCaller;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 集中装配分析层所有 Bean，将非 {@code @Component} 的 analysis 类纳入 Spring 容器管理。
 *
 * <p>analysis 包下的核心类（IssueRulesLoader、RuleFirstIssueClassifier 等）故意不做
 * {@code @Component} 或 {@code @Service}，由本配置类统一通过 {@code @Bean} 方法创建，
 * 使 Bean 的创建时机、参数绑定与生命周期完全可见，避免 component-scan 隐式装配带来的
 * 版本绑定与参数传递风险。
 *
 * <p>IssueRulesLoader 启动期解析 toml，失败让应用不启动，保证运行期不会用空规则集伪造
 * unclassified。</p>
 */
@Configuration
public class AnalysisConfiguration {

    // ── 配置值注入 ────────────────────────────────────────────────────────────
    @Value("${insightflow.analysis.ewma-alpha}")
    private double ewmaAlpha;

    @Value("${insightflow.analysis.min-history-days}")
    private int minHistoryDays;

    @Value("${insightflow.analysis.surge-z}")
    private double surgeZ;

    @Value("${insightflow.analysis.surge-min}")
    private int surgeMin;

    @Value("${insightflow.analysis.chronic-baseline}")
    private double chronicBaseline;

    @Value("${insightflow.analysis.longtail-max}")
    private int longtailMax;

    @Value("${insightflow.analysis.alert-cooldown-hours}")
    private int alertCooldownHours;

    @Value("${insightflow.analysis.global-alert-threshold}")
    private int globalAlertThreshold;

    /**
     * MVP 仅支持单一全局 Topic Pack；目录名对应 config/analysis/packs/{packDirectory}/。
     * 未来支持按 Workspace 动态绑定 Pack 时，此处应改为从 Workspace 配置读取而非固定值。
     */
    @Value("${insightflow.analysis.topic-pack-directory:game-chaoziran}")
    private String topicPackDirectory;

    // ── Bean 定义 ────────────────────────────────────────────────────────────

    /**
     * 规则加载器 Bean，通过 {@code new} + 显式 {@code load()} 手动控制生命周期，确保在
     * 配置阶段解析 toml 文件并绑定规则版本。不使用 {@code @Component} 是因为
     * AnalysisConfiguration 需要持有规则版本绑定时机，使调用方（如
     * WorkspaceProjectionCommandService）能通过注入的 Loader 读到冻结的版本号，避免
     * component-scan 在启动期无参初始化后再手动调用 load() 的断裂风险。
     */
    @Bean
    IssueRulesLoader issueRulesLoader() {
        IssueRulesLoader loader = new IssueRulesLoader();
        loader.load();
        return loader;
    }

    /**
     * 纯函数归一化 Bean，无状态，对 IssueRulesLoader 返回的归一映射做子串替换。
     * 暴露为 Bean 以便 ProjectionSourceLoader 通过 DI 引用，在加载事件前完成归一化，
     * 使源数据加载与归一化解耦。
     */
    @Bean
    IssueTextNormalizer issueTextNormalizer(IssueRulesLoader loader) {
        return new IssueTextNormalizer(loader.normalizeMappings());
    }

    /**
     * 规则优先分类器 Bean，按规则文件中的 priority 排序后逐条匹配事件文本。
     * 预留 Qwen 大模型分类器作为 IssueClassifier Port 的替代实现，当前仅使用规则优先策略。
     */
    @Bean
    RuleFirstIssueClassifier ruleFirstIssueClassifier(IssueRulesLoader loader) {
        return new RuleFirstIssueClassifier(loader.rules());
    }

    /**
     * 平台 L2 表达规则加载器 Bean，独立于 L1 的 IssueRulesLoader，
     * 因为 L2 是全平台固定枚举、跨 Workspace 共用同一份规则文件。
     */
    @Bean
    ExpressionRulesLoader expressionRulesLoader() {
        ExpressionRulesLoader loader = new ExpressionRulesLoader();
        loader.load();
        return loader;
    }

    /**
     * 平台 L2 表达分类器 Bean；无状态纯函数，规则来自 expressionRulesLoader。
     */
    @Bean
    ExpressionClassifier expressionClassifier(ExpressionRulesLoader loader) {
        return new ExpressionClassifier(loader.rules());
    }

    /**
     * Workspace Topic Pack 注册表；启动期扫描 packs 目录，支持按 Workspace 绑定解析。
     * 投影 L1 分类与 Dashboard Pack 展示均通过 Registry 取得实际生效的 Pack。
     */
    @Bean
    TopicPackRegistry topicPackRegistry() {
        TopicPackRegistry registry = new TopicPackRegistry(topicPackDirectory);
        registry.load();
        return registry;
    }

    /**
     * 兼容既有注入点：返回全局默认 Pack 的 Loader（等于 Registry 的默认回退 Pack）。
     */
    @Bean
    TopicPackLoader topicPackLoader(TopicPackRegistry registry) {
        return registry.requireByPackId(registry.defaultPackId());
    }

    /**
     * Pack LLM Topic Skill：全局开关 + Pack 开关 + LiteralChatModelCaller 齐备时用 Chat 实现，否则 NoOp。
     */
    @Bean
    TopicPackTopicLlmSkill topicPackTopicLlmSkill(
            @Autowired(required = false) LiteralChatModelCaller literalChatModelCaller,
            ObjectMapper objectMapper,
            OperationalPromptCatalog promptCatalog,
            TopicLlmSkillProperties properties) {
        if (properties.enabled() && literalChatModelCaller != null) {
            return new ChatTopicPackTopicLlmSkill(literalChatModelCaller, objectMapper, promptCatalog);
        }
        return new NoOpTopicPackTopicLlmSkill();
    }

    /** Pack L1 编排 Bean：规则优先，LLM 仅补 topic_general 子集。 */
    @Bean
    PackTopicClassifier packTopicClassifier(
            TopicPackTopicLlmSkill llmSkill, TopicLlmSkillProperties properties) {
        return new PackTopicClassifier(llmSkill, properties);
    }

    /**
     * DataCell 构建器 Bean，按规范 §4.3 配置三重守卫：
     * 单 cell 最大事件数 40（close_reason=count_limit）、
     * 单 cell 最大窗口跨度 60 分钟（close_reason=window_limit）、
     * 单 cell token 预算 6000（close_reason=token_limit）。
     * 参数硬编码在此以确保配置不可变，运行期不依赖外部配置源。
     */
    @Bean
    DataCellBuilder dataCellBuilder() {
        return new DataCellBuilder(40, 60, 6000);
    }

    /**
     * EWMA 基线服务 Bean，注入 IssueBaselineProfileRepository 和配置参数，
     * 用于计算异常基线、检测 surge 与 chronic 模式。
     */
    @Bean
    EwmaBaselineService ewmaBaselineService(IssueBaselineProfileRepository profileRepo) {
        return new EwmaBaselineService(profileRepo, ewmaAlpha, minHistoryDays,
                surgeZ, surgeMin, chronicBaseline, longtailMax);
    }

    /**
     * 告警检测器 Bean，在 EWMA 基线之上叠加冷却期和全局阈值过滤。
     * 使用新的 ObjectMapper() 实例，避免与 Spring 的 ObjectMapper 共享配置。
     */
    @Bean
    AlertDetector alertDetector(
            AlertRepository alertRepo, EwmaBaselineService ewmaBaselineService, ApplicationEventPublisher eventPublisher) {
        return new AlertDetector(alertRepo, ewmaBaselineService,
                alertCooldownHours, globalAlertThreshold, surgeZ, new ObjectMapper(), eventPublisher);
    }
}
