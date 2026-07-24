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
import com.insightflow.service.WorkspaceService;
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
            ObjectMapper objectMapper) {
        this(
                workspaceService,
                catalogRepository,
                metricRepository,
                alertRepository,
                cellIssueRepository,
                feedbackEventRepository,
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
            ObjectMapper objectMapper,
            Clock clock) {
        this.workspaceService = workspaceService;
        this.catalogRepository = catalogRepository;
        this.metricRepository = metricRepository;
        this.alertRepository = alertRepository;
        this.cellIssueRepository = cellIssueRepository;
        this.feedbackEventRepository = feedbackEventRepository;
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
            evidence.add(executeTool(tool, workspaceId, catalogs, issue));
        }
        return new InvestigationResult(plan, evidence);
    }

    /** 统一分派到固定 Tool 实现；没有 default 分支，新增枚举成员必须明确实现其数据边界。 */
    private InvestigationEvidence executeTool(
            InvestigationToolType tool,
            Long workspaceId,
            List<IssueCatalog> catalogs,
            Optional<IssueCatalog> issue) {
        return switch (tool) {
            case ISSUE_TREND -> issueTrend(workspaceId, issue);
            case TOPIC_DISTRIBUTION -> topicDistribution(workspaceId, catalogs);
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
