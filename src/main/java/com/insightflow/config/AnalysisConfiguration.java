package com.insightflow.config;

import com.insightflow.service.analysis.DataCellBuilder;
import com.insightflow.service.analysis.IssueRulesLoader;
import com.insightflow.service.analysis.IssueTextNormalizer;
import com.insightflow.service.analysis.RuleFirstIssueClassifier;
import com.insightflow.service.analysis.AlertDetector;
import com.insightflow.service.analysis.EwmaBaselineService;
import com.insightflow.repository.AlertRepository;
import com.insightflow.repository.IssueBaselineProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    @Value("${analysis.ewma-alpha}")
    private double ewmaAlpha;

    @Value("${analysis.min-history-days}")
    private int minHistoryDays;

    @Value("${analysis.surge-z}")
    private double surgeZ;

    @Value("${analysis.surge-min}")
    private int surgeMin;

    @Value("${analysis.chronic-baseline}")
    private double chronicBaseline;

    @Value("${analysis.longtail-max}")
    private int longtailMax;

    @Value("${analysis.alert-cooldown-hours}")
    private int alertCooldownHours;

    @Value("${analysis.global-alert-threshold}")
    private int globalAlertThreshold;

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
    AlertDetector alertDetector(AlertRepository alertRepo, EwmaBaselineService ewmaBaselineService) {
        return new AlertDetector(alertRepo, ewmaBaselineService,
                alertCooldownHours, globalAlertThreshold, surgeZ, new ObjectMapper());
    }
}
