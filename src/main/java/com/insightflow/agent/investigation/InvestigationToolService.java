package com.insightflow.agent.investigation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.Alert;
import com.insightflow.entity.CellIssue;
import com.insightflow.entity.FeedbackEvent;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.entity.IssueMetricBucket;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.AlertRepository;
import com.insightflow.repository.CellIssueRepository;
import com.insightflow.repository.FeedbackEventRepository;
import com.insightflow.repository.IssueCatalogRepository;
import com.insightflow.repository.IssueMetricBucketRepository;
import com.insightflow.service.DashboardService;
import com.insightflow.service.WorkspaceService;
import com.insightflow.service.analysis.ExpressionDefaults;
import com.insightflow.service.analysis.ExpressionRule;
import com.insightflow.service.analysis.ExpressionRulesLoader;
import com.insightflow.investigation.window.InvestigationWindow;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P2 的受控只读调查 Tool 服务。
 *
 * <p>服务只接受可信的 Workspace UUID、原始问题和由规划器生成的 Tool 白名单；内部先解析 Workspace，
 * 再以 workspace_id 限定每个仓储调用。它不接收 SQL、内部 ID、动态字段或动态日期范围，也不写入任何业务数据。</p>
 */
@Service
@Transactional(readOnly = true)
public class InvestigationToolService {

    /** 单次回答只保留最近两周聚合指标，为本周与上周比较提供固定且可审计的窗口。 */
    private static final int HISTORY_DAYS = 14;

    /** 主题分布最多展示五项，防止全部目录进入模型上下文挤占证据空间。 */
    private static final int TOPIC_LIMIT = 5;

    /** 反馈样本最多五条，既满足调查需要又限制脱敏文本暴露范围。 */
    private static final int SAMPLE_LIMIT = 5;

    /** 每条脱敏样本文本最多 200 字符，防止单条异常内容主导模型判断。 */
    private static final int SAMPLE_TEXT_LIMIT = 200;

    /** Workspace 服务是外部 UUID 转内部隔离键的唯一入口。 */
    private final WorkspaceService workspaceService;

    /** 主题目录只用于解析当前工作区中的用户可读主题名称和 key。 */
    private final IssueCatalogRepository catalogRepository;

    /** 日指标桶是趋势、主题分布和时间范围比较的唯一计数来源。 */
    private final IssueMetricBucketRepository metricRepository;

    /** 告警仓储返回触发时冻结的计数、EWMA 与 z-score，不重新推导历史异常。 */
    private final AlertRepository alertRepository;

    /** Cell-主题记录只提供有限样本事件引用，不能直接作为模型上下文。 */
    private final CellIssueRepository cellIssueRepository;

    /** 反馈事件仅读取已脱敏文本，且会再次校验 workspace_id。 */
    private final FeedbackEventRepository feedbackEventRepository;

    /** 看板服务提供 L2 分布、L2→L1 钻取与交叉样本的只读聚合，与 Dashboard API 同源。 */
    private final DashboardService dashboardService;

    /** 平台 L2 规则用于从用户问题解析 expr_* 键与中文展示名，不执行分类写入。 */
    private final ExpressionRulesLoader expressionRulesLoader;

    /** JSON 仅用于解析服务端保存的有限样本事件引用数组。 */
    private final ObjectMapper objectMapper;

    /** 注入时钟使固定时间窗口可测试，生产默认使用 UTC 时钟。 */
    private final Clock clock;

    /** Spring 生产构造器固定使用 UTC，避免服务器时区改变“本周”统计边界。 */
    @Autowired
    public InvestigationToolService(
            WorkspaceService workspaceService,
            IssueCatalogRepository catalogRepository,
            IssueMetricBucketRepository metricRepository,
            AlertRepository alertRepository,
            CellIssueRepository cellIssueRepository,
            FeedbackEventRepository feedbackEventRepository,
            DashboardService dashboardService,
            ExpressionRulesLoader expressionRulesLoader,
            ObjectMapper objectMapper) {
        this(
                workspaceService,
                catalogRepository,
                metricRepository,
                alertRepository,
                cellIssueRepository,
                feedbackEventRepository,
                dashboardService,
                expressionRulesLoader,
                objectMapper,
                Clock.systemUTC());
    }

    /** 包可见构造器仅为固定时钟的单元测试保留，生产代码不得传入用户控制的时钟。 */
    InvestigationToolService(
            WorkspaceService workspaceService,
            IssueCatalogRepository catalogRepository,
            IssueMetricBucketRepository metricRepository,
            AlertRepository alertRepository,
            CellIssueRepository cellIssueRepository,
            FeedbackEventRepository feedbackEventRepository,
            DashboardService dashboardService,
            ExpressionRulesLoader expressionRulesLoader,
            ObjectMapper objectMapper,
            Clock clock) {
        this.workspaceService = workspaceService;
        this.catalogRepository = catalogRepository;
        this.metricRepository = metricRepository;
        this.alertRepository = alertRepository;
        this.cellIssueRepository = cellIssueRepository;
        this.feedbackEventRepository = feedbackEventRepository;
        this.dashboardService = dashboardService;
        this.expressionRulesLoader = expressionRulesLoader;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 按既定计划执行 Tool；工具顺序由规划器决定，调用方无法追加 Tool 或用问题文本改变仓储查询形状。
     */
    public InvestigationResult investigate(
            java.util.UUID workspacePublicId,
            String question,
            InvestigationPlan plan) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        Long workspaceId = workspace.getId();
        List<IssueCatalog> catalogs = catalogRepository.findByWorkspaceId(workspaceId);
        Optional<IssueCatalog> issue = resolveIssue(question, catalogs);
        List<InvestigationEvidence> evidence = new ArrayList<>();
        for (InvestigationToolType tool : plan.tools()) {
            evidence.add(executeTool(tool, workspacePublicId, workspaceId, question, catalogs, issue));
        }
        return new InvestigationResult(plan, evidence);
    }

    /**
     * 为异步告警调查执行冻结窗口内的最小证据集。
     *
     * <p>这里不接受日期文本，也不读取 {@link #now()}：所有边界已在任务入队前根据 Alert 锚点冻结。
     * 因此租约丢失后的重试仍查询同一批日桶、告警和样本。</p>
     */
    public List<InvestigationEvidence> investigateForAlert(
            UUID workspacePublicId, Alert currentAlert, IssueCatalog issue, List<InvestigationWindow> windows) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        if (!workspace.getId().equals(issue.getWorkspaceId())
                || !workspace.getId().equals(currentAlert.getWorkspaceId())
                || !issue.getId().equals(currentAlert.getIssueId())) {
            throw new IllegalArgumentException("主题不属于当前工作区");
        }
        List<InvestigationEvidence> evidence = new ArrayList<>();
        // 历史是“本次告警之前”的调查级事实，不随 SHORT_TERM/WEEKLY 改变，BOTH 时只生成一次。
        evidence.add(alertHistoryForAlert(workspace.getId(), issue, currentAlert));
        for (InvestigationWindow window : windows) {
            evidence.add(issueTrendForWindow(workspace.getId(), issue, window));
            evidence.add(sampleFeedbackForWindow(workspace.getId(), issue, window));
            evidence.add(periodComparisonForWindow(workspace.getId(), issue, window));
        }
        return List.copyOf(evidence);
    }

    /** 日桶趋势只展开当前窗口，不把前序汇总混入“窗口内如何变化”的回答。 */
    private InvestigationEvidence issueTrendForWindow(Long workspaceId, IssueCatalog issue, InvestigationWindow window) {
        List<IssueMetricBucket> buckets = metricRepository
                .findByWorkspaceIdAndBucketStartGreaterThanEqual(workspaceId, window.currentStart()).stream()
                .filter(bucket -> issue.getId().equals(bucket.getIssueId()))
                .filter(bucket -> bucket.getBucketStart().isBefore(window.currentEnd()))
                .sorted(Comparator.comparing(IssueMetricBucket::getBucketStart))
                .toList();
        String id = "trend:" + issue.getCanonicalKey() + ":" + window.type();
        if (buckets.isEmpty()) {
            return insufficient(InvestigationToolType.ISSUE_TREND, id, "主题趋势（" + window.type() + "）",
                    windowDescription(window) + "；来源 issue_metric_bucket（日粒度）；当前窗口没有可用日桶。");
        }
        String series = buckets.stream()
                .map(bucket -> String.format("bucketStart=%s，feedbackCount=%d", bucket.getBucketStart(), bucket.getFeedbackCount()))
                .collect(java.util.stream.Collectors.joining("；"));
        return evidence(InvestigationToolType.ISSUE_TREND, "trend:" + issue.getCanonicalKey() + ":" + window.type(),
                "主题趋势（" + window.type() + "）",
                windowDescription(window) + "；来源 issue_metric_bucket（日粒度）；" + series + "。", true);
    }

    /** 告警历史严格使用创建顺序，排除当前告警及同一排序点之后的未来告警。 */
    private InvestigationEvidence alertHistoryForAlert(Long workspaceId, IssueCatalog issue, Alert currentAlert) {
        List<Alert> alerts = alertRepository.findByWorkspaceIdAndIssueIdOrderByCreatedAtDesc(workspaceId, issue.getId()).stream()
                .filter(alert -> isBeforeCurrentAlert(alert, currentAlert))
                .limit(TOPIC_LIMIT)
                .toList();
        if (alerts.isEmpty()) {
            return insufficient(InvestigationToolType.ALERT_HISTORY, "alerts:" + issue.getCanonicalKey(),
                    "历史告警与基线", "当前告警之前没有可复核的同主题告警记录。");
        }
        String summary = alerts.stream().map(alert -> String.format("%s: 当前值 %d，z-score %.1f，状态 %s",
                alert.getBucketStart(), alert.getCurrentCount(), alert.getZScore(), alert.getStatus())).collect(java.util.stream.Collectors.joining("；"));
        return evidence(InvestigationToolType.ALERT_HISTORY, "alerts:" + issue.getCanonicalKey(),
                "历史告警与基线", "来源 alert；" + summary + "。", true);
    }

    /** 样本同时按 workspace 与冻结的当前窗口过滤；不足时显式返回不足，不能借用窗口外样本。 */
    private InvestigationEvidence sampleFeedbackForWindow(Long workspaceId, IssueCatalog issue, InvestigationWindow window) {
        LinkedHashSet<Long> eventIds = new LinkedHashSet<>();
        for (CellIssue cellIssue : cellIssueRepository.findByIssueId(issue.getId())) {
            if (workspaceId.equals(cellIssue.getWorkspaceId())) {
                eventIds.addAll(parseSampleIds(cellIssue.getSampleEventIdsJson()));
            }
        }
        List<String> samples = eventIds.stream().map(feedbackEventRepository::findById).flatMap(Optional::stream)
                .filter(event -> workspaceId.equals(event.getWorkspaceId()))
                .filter(event -> !event.getOccurredAt().isBefore(window.currentStart()) && event.getOccurredAt().isBefore(window.currentEnd()))
                .map(FeedbackEvent::getSanitizedText).filter(text -> text != null && !text.isBlank())
                .map(this::capSampleText).limit(SAMPLE_LIMIT).toList();
        String id = "samples:" + issue.getCanonicalKey() + ":" + window.type();
        if (samples.isEmpty()) {
            return insufficient(InvestigationToolType.SAMPLE_FEEDBACK, id, "脱敏样本（" + window.type() + "）",
                    windowDescription(window) + "；冻结窗口内没有可用的脱敏反馈样本；不使用窗口外样本补足。");
        }
        return evidence(InvestigationToolType.SAMPLE_FEEDBACK, id, "脱敏样本（" + window.type() + "）",
                windowDescription(window) + "；来源 feedback_event；样本：" + String.join("；", samples), true);
    }

    /** 比较结果与趋势共用同一冻结边界，避免出现两个 Tool 对“本周”的不同解释。 */
    private InvestigationEvidence periodComparisonForWindow(Long workspaceId, IssueCatalog issue, InvestigationWindow window) {
        List<IssueMetricBucket> buckets = metricRepository
                .findByWorkspaceIdAndBucketStartGreaterThanEqual(workspaceId, window.previousStart()).stream()
                .filter(bucket -> bucket.getBucketStart().isBefore(window.currentEnd()))
                .toList();
        int current = sumBuckets(buckets, issue.getId(), window.currentStart(), window.currentEnd());
        int previous = sumBuckets(buckets, issue.getId(), window.previousStart(), window.previousEnd());
        int delta = current - previous;
        String id = "comparison:" + issue.getCanonicalKey() + ":" + window.type();
        if (current == 0 && previous == 0) {
            return insufficient(InvestigationToolType.PERIOD_COMPARISON, id, "时间范围比较（" + window.type() + "）",
                    windowDescription(window) + "；冻结窗口内没有可比较的主题日指标。");
        }
        String percentage = previous == 0 ? "percentageChange=unavailable，newActivity=true"
                : String.format("percentageChange=%+.1f%%，newActivity=false", (delta * 100.0) / previous);
        return evidence(InvestigationToolType.PERIOD_COMPARISON, id, "时间范围比较（" + window.type() + "）",
                String.format("%s；来源 issue_metric_bucket；currentCount=%d，previousCount=%d，absoluteDelta=%+d，%s。",
                        windowDescription(window), current, previous, delta, percentage), true);
    }

    /** 计划中的窗口字段全部写入证据，避免模型或人工把“当前窗口”误解为执行当天。 */
    private String windowDescription(InvestigationWindow window) {
        return String.format("windowType=%s，anchorTime=%s，currentStart=%s，currentEnd=%s，previousStart=%s，previousEnd=%s",
                window.type(), window.anchorTime(), window.currentStart(), window.currentEnd(), window.previousStart(), window.previousEnd());
    }

    /** createdAt 相同时用持久化 ID 打破平局，保证同桶多告警的历史排序稳定。 */
    private boolean isBeforeCurrentAlert(Alert candidate, Alert currentAlert) {
        if (candidate.getId().equals(currentAlert.getId())) {
            return false;
        }
        int createdOrder = candidate.getCreatedAt().compareTo(currentAlert.getCreatedAt());
        return createdOrder < 0 || (createdOrder == 0 && candidate.getId() < currentAlert.getId());
    }

    /** 统一分派到固定 Tool 实现；没有 default 分支，新增枚举成员必须明确实现其数据边界。 */
    private InvestigationEvidence executeTool(
            InvestigationToolType tool,
            UUID workspacePublicId,
            Long workspaceId,
            String question,
            List<IssueCatalog> catalogs,
            Optional<IssueCatalog> issue) {
        return switch (tool) {
            case ISSUE_TREND -> issueTrend(workspaceId, issue);
            case TOPIC_DISTRIBUTION -> topicDistribution(workspaceId, catalogs);
            case EXPRESSION_DISTRIBUTION -> expressionDistribution(workspacePublicId);
            case EXPRESSION_TOPIC_DRILLDOWN -> expressionTopicDrilldown(workspacePublicId, question);
            case EXPRESSION_TOPIC_SAMPLES -> expressionTopicSamples(workspacePublicId, question, catalogs);
            case ALERT_HISTORY -> alertHistory(workspaceId, catalogs, issue);
            case SAMPLE_FEEDBACK -> sampleFeedback(workspaceId, issue);
            case PERIOD_COMPARISON -> periodComparison(workspaceId, issue);
            case DATA_AVAILABILITY -> versionDataAvailability();
            case KNOWLEDGE_SEARCH -> throw new IllegalArgumentException("知识检索由独立受控 Tool 执行");
        };
    }

    /**
     * 只在当前工作区目录中匹配用户问题中的主题名称或 canonical key；多候选时选择名称更长者以减少短词误匹配。
     */
    private Optional<IssueCatalog> resolveIssue(String question, List<IssueCatalog> catalogs) {
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
        return catalogs.stream()
                .filter(catalog -> normalized.contains(catalog.getCanonicalName().toLowerCase(Locale.ROOT))
                        || normalized.contains(catalog.getCanonicalKey().toLowerCase(Locale.ROOT)))
                .max(Comparator.comparingInt(catalog -> catalog.getCanonicalName().length()));
    }

    /** 趋势 Tool 读取固定两周日指标，并将本周与上周总数作为可引用聚合事实。 */
    private InvestigationEvidence issueTrend(Long workspaceId, Optional<IssueCatalog> issue) {
        if (issue.isEmpty()) {
            return insufficient(InvestigationToolType.ISSUE_TREND, "trend:unresolved", "主题趋势", "未识别具体主题，无法查询单主题趋势。");
        }
        IssueCatalog catalog = issue.get();
        List<IssueMetricBucket> buckets = recentBuckets(workspaceId);
        int current = sumBuckets(buckets, catalog.getId(), now().minusDays(7), now());
        int previous = sumBuckets(buckets, catalog.getId(), now().minusDays(HISTORY_DAYS), now().minusDays(7));
        String content = String.format(
                "来源 issue_metric_bucket；%s 最近7天 %d 条，前7天 %d 条。",
                catalog.getCanonicalName(), current, previous);
        return evidence(InvestigationToolType.ISSUE_TREND, "trend:" + catalog.getCanonicalKey() + ":last_14_days", "主题趋势", content, true);
    }

    /** 主题分布 Tool 只返回最近七天的前五项，未命中主题的泛问答不读取样本文本。 */
    private InvestigationEvidence topicDistribution(Long workspaceId, List<IssueCatalog> catalogs) {
        List<IssueMetricBucket> buckets = recentBuckets(workspaceId);
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (IssueCatalog catalog : catalogs) {
            counts.put(catalog.getId(), sumBuckets(buckets, catalog.getId(), now().minusDays(7), now()));
        }
        List<String> topTopics = catalogs.stream()
                .sorted(Comparator.comparingInt((IssueCatalog catalog) -> counts.get(catalog.getId())).reversed())
                .filter(catalog -> counts.get(catalog.getId()) > 0)
                .limit(TOPIC_LIMIT)
                .map(catalog -> catalog.getCanonicalName() + " " + counts.get(catalog.getId()) + " 条")
                .toList();
        if (topTopics.isEmpty()) {
            return insufficient(InvestigationToolType.TOPIC_DISTRIBUTION, "distribution:last_7_days", "主题分布", "最近7天没有可用的主题聚合数据。");
        }
        return evidence(
                InvestigationToolType.TOPIC_DISTRIBUTION,
                "distribution:last_7_days",
                "主题分布",
                "来源 issue_metric_bucket；最近7天 Top" + TOPIC_LIMIT + "：" + String.join("；", topTopics) + "。",
                true);
    }

    /**
     * L2 表达分布 Tool 复用看板首屏同一分析窗口与标注聚合逻辑，返回五类表达占比摘要。
     *
     * <p>时间边界由 {@link DashboardService} 的 {@code AnalysisWindowResolver} 决定，
     * 不接受用户消息中的动态日期，与 L1 Tool 的固定 UTC 窗口策略并存但 L2 与 Dashboard 口径一致。</p>
     */
    private InvestigationEvidence expressionDistribution(UUID workspacePublicId) {
        DashboardService.DashboardResponse dashboard = dashboardService.getDashboard(workspacePublicId, null, null);
        List<DashboardService.ExpressionCount> distribution = dashboard.expressionSummary().distribution();
        int total = distribution.stream().mapToInt(DashboardService.ExpressionCount::feedbackCount).sum();
        if (total == 0) {
            return insufficient(
                    InvestigationToolType.EXPRESSION_DISTRIBUTION,
                    "expression:distribution",
                    "L2 表达分布",
                    "当前分析窗口内没有可用的 L2 表达标注数据。");
        }
        List<String> parts = distribution.stream()
                .filter(item -> item.feedbackCount() > 0)
                .map(item -> item.name() + " " + item.feedbackCount() + " 条")
                .toList();
        DashboardService.WindowInfo window = dashboard.analysisWindow();
        String content = String.format(
                "来源 feedback_projection_annotation；分析窗口 %s 至 %s；L2 表达分布：%s。",
                window.start(),
                window.end(),
                String.join("；", parts));
        return evidence(
                InvestigationToolType.EXPRESSION_DISTRIBUTION,
                "expression:distribution",
                "L2 表达分布",
                content,
                true);
    }

    /**
     * L2→L1 钻取 Tool：在用户问题中识别某一 L2 表达类目后，返回该类目下 Top N 议题分布。
     *
     * <p>未识别表达类型时返回数据不足，避免把全工作区 L1 分布误当作某类表达的交叉结果。</p>
     */
    private InvestigationEvidence expressionTopicDrilldown(UUID workspacePublicId, String question) {
        Optional<String> expressionKey = resolveExpressionKey(question);
        if (expressionKey.isEmpty()) {
            return insufficient(
                    InvestigationToolType.EXPRESSION_TOPIC_DRILLDOWN,
                    "expression:topics:unresolved",
                    "L2→L1 交叉分布",
                    "未识别具体 L2 表达类型（如建议/吐槽/好评），无法查询交叉议题分布。");
        }
        String key = expressionKey.get();
        DashboardService.ExpressionTopicsResponse response =
                dashboardService.getExpressionTopics(workspacePublicId, key, null, null);
        if (response.topics().isEmpty()) {
            return insufficient(
                    InvestigationToolType.EXPRESSION_TOPIC_DRILLDOWN,
                    "expression:topics:" + key,
                    "L2→L1 交叉分布",
                    expressionDisplayName(key) + " 在当前分析窗口内没有可关联的 L1 议题数据。");
        }
        List<String> topTopics = response.topics().stream()
                .limit(TOPIC_LIMIT)
                .map(topic -> topic.canonicalName() + " " + topic.feedbackCount() + " 条")
                .toList();
        DashboardService.WindowInfo window = response.analysisWindow();
        String content = String.format(
                "来源 annotation ⋈ issue_link；%s（%s）下 Top%d 议题；分析窗口 %s 至 %s：%s。",
                expressionDisplayName(key),
                key,
                TOPIC_LIMIT,
                window.start(),
                window.end(),
                String.join("；", topTopics));
        return evidence(
                InvestigationToolType.EXPRESSION_TOPIC_DRILLDOWN,
                "expression:topics:" + key,
                "L2→L1 交叉分布",
                content,
                true);
    }

    /**
     * L2×L1 交叉样本 Tool：需同时识别 L2 表达类型与 L1 主题，最多返回五条脱敏文本。
     *
     * <p>与 Dashboard 交叉样本 API 同源，再次截断单条文本长度，不暴露事件内部主键。</p>
     */
    private InvestigationEvidence expressionTopicSamples(
            UUID workspacePublicId, String question, List<IssueCatalog> catalogs) {
        Optional<String> expressionKey = resolveExpressionKey(question);
        Optional<IssueCatalog> issue = resolveIssue(question, catalogs);
        if (expressionKey.isEmpty()) {
            return insufficient(
                    InvestigationToolType.EXPRESSION_TOPIC_SAMPLES,
                    "expression:samples:unresolved",
                    "L2×L1 脱敏样本",
                    "未识别具体 L2 表达类型，无法安全抽取交叉样本。");
        }
        if (issue.isEmpty()) {
            return insufficient(
                    InvestigationToolType.EXPRESSION_TOPIC_SAMPLES,
                    "expression:samples:topic_unresolved",
                    "L2×L1 脱敏样本",
                    "未识别具体 L1 主题，无法安全抽取 L2×L1 交叉样本。");
        }
        String key = expressionKey.get();
        IssueCatalog catalog = issue.get();
        List<DashboardService.FeedbackSample> samples = dashboardService.getExpressionTopicSamples(
                workspacePublicId, key, catalog.getCanonicalKey(), null, null);
        List<String> texts = samples.stream()
                .map(DashboardService.FeedbackSample::text)
                .filter(text -> text != null && !text.isBlank())
                .map(this::capSampleText)
                .limit(SAMPLE_LIMIT)
                .toList();
        if (texts.isEmpty()) {
            return insufficient(
                    InvestigationToolType.EXPRESSION_TOPIC_SAMPLES,
                    "expression:samples:" + key + ":" + catalog.getCanonicalKey(),
                    "L2×L1 脱敏样本",
                    expressionDisplayName(key) + " × " + catalog.getCanonicalName() + " 没有可用的脱敏反馈样本。");
        }
        return evidence(
                InvestigationToolType.EXPRESSION_TOPIC_SAMPLES,
                "expression:samples:" + key + ":" + catalog.getCanonicalKey(),
                "L2×L1 脱敏样本",
                "来源 feedback_event；"
                        + expressionDisplayName(key)
                        + " × "
                        + catalog.getCanonicalName()
                        + " 样本："
                        + String.join("；", texts),
                true);
    }

    /**
     * 从用户问题中解析 L2 表达键：优先匹配 expr_* 键，再匹配中文展示名与规则正向词，同分取 priority 更高者。
     */
    private Optional<String> resolveExpressionKey(String question) {
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
        for (ExpressionRule rule : expressionRulesLoader.rules()) {
            if (normalized.contains(rule.canonicalKey().toLowerCase(Locale.ROOT))) {
                return Optional.of(rule.canonicalKey());
            }
        }
        if (normalized.contains(ExpressionDefaults.EXPR_OTHER_KEY)) {
            return Optional.of(ExpressionDefaults.EXPR_OTHER_KEY);
        }
        Optional<ExpressionRule> byName = expressionRulesLoader.rules().stream()
                .filter(rule -> expressionNameMatches(normalized, rule.name()))
                .max(Comparator.comparingInt(rule -> rule.name().length()));
        if (byName.isPresent()) {
            return Optional.of(byName.get().canonicalKey());
        }
        if (normalized.contains(ExpressionDefaults.EXPR_OTHER_NAME.toLowerCase(Locale.ROOT))) {
            return Optional.of(ExpressionDefaults.EXPR_OTHER_KEY);
        }
        return expressionRulesLoader.rules().stream()
                .filter(rule -> rule.anyPatterns().stream()
                        .anyMatch(pattern -> normalized.contains(pattern.toLowerCase(Locale.ROOT))))
                .max(Comparator.comparingInt(ExpressionRule::priority))
                .map(ExpressionRule::canonicalKey);
    }

    /** 匹配 L2 中文展示名或其 "/" 分隔的短名片段，便于识别"吐槽""建议"等口语问法。 */
    private boolean expressionNameMatches(String normalized, String name) {
        if (normalized.contains(name.toLowerCase(Locale.ROOT))) {
            return true;
        }
        for (String part : name.split("/")) {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isBlank() && normalized.contains(trimmed)) {
                return true;
            }
        }
        return false;
    }

    /** 将 expr_* 键映射为平台规则中的中文展示名，证据正文对用户可读。 */
    private String expressionDisplayName(String expressionKey) {
        return expressionRulesLoader.rules().stream()
                .filter(rule -> rule.canonicalKey().equals(expressionKey))
                .map(ExpressionRule::name)
                .findFirst()
                .orElse(ExpressionDefaults.EXPR_OTHER_NAME);
    }

    /** 告警 Tool 只读取工作区范围的告警快照；有主题时进一步收窄到该主题。 */
    private InvestigationEvidence alertHistory(
            Long workspaceId,
            List<IssueCatalog> catalogs,
            Optional<IssueCatalog> issue) {
        List<Alert> alerts = issue
                .map(catalog -> alertRepository.findByWorkspaceIdAndIssueIdOrderByCreatedAtDesc(workspaceId, catalog.getId()))
                .orElseGet(() -> alertRepository.findTop5ByWorkspaceIdOrderByCreatedAtDesc(workspaceId));
        if (alerts.isEmpty()) {
            return insufficient(InvestigationToolType.ALERT_HISTORY, "alerts:recent", "告警历史", "当前查询范围内没有告警记录。");
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (IssueCatalog catalog : catalogs) {
            names.put(catalog.getId(), catalog.getCanonicalName());
        }
        List<String> summaries = alerts.stream().limit(TOPIC_LIMIT).map(alert -> String.format(
                "%s 当前值 %d、EWMA %.1f、z-score %.1f、状态 %s",
                names.getOrDefault(alert.getIssueId(), "未知主题"),
                alert.getCurrentCount(),
                alert.getBaselineEwma(),
                alert.getZScore(),
                alert.getStatus())).toList();
        return evidence(
                InvestigationToolType.ALERT_HISTORY,
                issue.map(value -> "alerts:" + value.getCanonicalKey()).orElse("alerts:recent"),
                "告警与基线",
                "来源 alert；" + String.join("；", summaries) + "。",
                true);
    }

    /** 样本 Tool 通过 CellIssue 间接读取有限事件，并再次过滤 Cell 与事件两侧的 workspace_id。 */
    private InvestigationEvidence sampleFeedback(Long workspaceId, Optional<IssueCatalog> issue) {
        if (issue.isEmpty()) {
            return insufficient(InvestigationToolType.SAMPLE_FEEDBACK, "samples:unresolved", "脱敏样本", "未识别具体主题，无法安全抽取反馈样本。");
        }
        IssueCatalog catalog = issue.get();
        LinkedHashSet<Long> eventIds = new LinkedHashSet<>();
        for (CellIssue cellIssue : cellIssueRepository.findByIssueId(catalog.getId())) {
            if (!workspaceId.equals(cellIssue.getWorkspaceId())) {
                continue;
            }
            eventIds.addAll(parseSampleIds(cellIssue.getSampleEventIdsJson()));
            if (eventIds.size() >= SAMPLE_LIMIT) {
                break;
            }
        }
        List<String> samples = eventIds.stream().limit(SAMPLE_LIMIT)
                .map(feedbackEventRepository::findById)
                .flatMap(Optional::stream)
                .filter(event -> workspaceId.equals(event.getWorkspaceId()))
                .map(FeedbackEvent::getSanitizedText)
                .filter(text -> text != null && !text.isBlank())
                .map(this::capSampleText)
                .limit(SAMPLE_LIMIT)
                .toList();
        if (samples.isEmpty()) {
            return insufficient(InvestigationToolType.SAMPLE_FEEDBACK, "samples:" + catalog.getCanonicalKey(), "脱敏样本", "当前主题没有可用的脱敏反馈样本。");
        }
        return evidence(
                InvestigationToolType.SAMPLE_FEEDBACK,
                "samples:" + catalog.getCanonicalKey(),
                "脱敏样本",
                "来源 feedback_event；样本：" + String.join("；", samples),
                true);
    }

    /** 时间范围比较固定为当前七天和前七天；没有主题时比较工作区整体指标并明确标注范围。 */
    private InvestigationEvidence periodComparison(Long workspaceId, Optional<IssueCatalog> issue) {
        List<IssueMetricBucket> buckets = recentBuckets(workspaceId);
        Long issueId = issue.map(IssueCatalog::getId).orElse(null);
        int current = sumBuckets(buckets, issueId, now().minusDays(7), now());
        int previous = sumBuckets(buckets, issueId, now().minusDays(HISTORY_DAYS), now().minusDays(7));
        String scope = issue.map(IssueCatalog::getCanonicalName).orElse("全工作区主题聚合");
        if (current == 0 && previous == 0) {
            return insufficient(InvestigationToolType.PERIOD_COMPARISON, "comparison:last_14_days", "时间范围比较", scope + " 在最近14天没有可比较的指标数据。");
        }
        int delta = current - previous;
        return evidence(
                InvestigationToolType.PERIOD_COMPARISON,
                "comparison:" + issue.map(IssueCatalog::getCanonicalKey).orElse("workspace") + ":last_14_days",
                "时间范围比较",
                String.format("来源 issue_metric_bucket；%s 最近7天 %d 条，前7天 %d 条，绝对变化 %s%d 条。", scope, current, previous, delta >= 0 ? "+" : "", delta),
                true);
    }

    /** P2 尚未接入版本或活动事件表，因此明确暴露数据缺口而不输出任何版本因果推断。 */
    private InvestigationEvidence versionDataAvailability() {
        return insufficient(
                InvestigationToolType.DATA_AVAILABILITY,
                "availability:version_event",
                "版本数据可用性",
                "当前未接入版本或活动事件数据，不能确认版本前后变化，更不能据此判断因果。");
    }

    /** 读取固定两周窗口的当前工作区指标；结束时间在内存中二次过滤，避免依赖开放式日期参数。 */
    private List<IssueMetricBucket> recentBuckets(Long workspaceId) {
        OffsetDateTime start = now().minusDays(HISTORY_DAYS);
        return metricRepository.findByWorkspaceIdAndBucketStartGreaterThanEqual(workspaceId, start).stream()
                .filter(bucket -> !bucket.getBucketStart().isAfter(now()))
                .toList();
    }

    /** 汇总某主题或全部主题在半开时间窗口内的指标，防止相邻周边界重复计数。 */
    private int sumBuckets(List<IssueMetricBucket> buckets, Long issueId, OffsetDateTime startInclusive, OffsetDateTime endExclusive) {
        return buckets.stream()
                .filter(bucket -> issueId == null || issueId.equals(bucket.getIssueId()))
                .filter(bucket -> !bucket.getBucketStart().isBefore(startInclusive))
                .filter(bucket -> bucket.getBucketStart().isBefore(endExclusive))
                .mapToInt(IssueMetricBucket::getFeedbackCount)
                .sum();
    }

    /** 解析服务端生成的 JSON 数组；损坏快照只导致该条样本不可用，不泄露解析异常或原始 JSON。 */
    private List<Long> parseSampleIds(String sampleEventIdsJson) {
        try {
            return objectMapper.readValue(sampleEventIdsJson, new TypeReference<List<Long>>() { });
        } catch (Exception exception) {
            return List.of();
        }
    }

    /** 样本文本按字符上限截断，后缀只表明服务端主动截断而非原文内容的一部分。 */
    private String capSampleText(String text) {
        return text.length() <= SAMPLE_TEXT_LIMIT ? text : text.substring(0, SAMPLE_TEXT_LIMIT) + "…";
    }

    /** 使用服务端固定 UTC 时钟获得当前窗口终点，禁止由用户消息决定时间边界。 */
    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    /** 创建可用证据；标题和正文均来自固定 Tool 逻辑而非用户自由文本。 */
    private InvestigationEvidence evidence(
            InvestigationToolType tool, String id, String title, String content, boolean sufficient) {
        return new InvestigationEvidence(id, tool, title, content, sufficient);
    }

    /** 创建数据不足证据，保持模型能说明未知项而不是把空列表误读为业务结论。 */
    private InvestigationEvidence insufficient(InvestigationToolType tool, String id, String title, String content) {
        return evidence(tool, id, title, content, false);
    }
}
