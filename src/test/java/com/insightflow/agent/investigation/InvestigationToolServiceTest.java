package com.insightflow.agent.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.insightflow.service.analysis.ExpressionRulesLoader;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 只读调查 Tool 的服务层测试。
 *
 * <p>测试验证 Tool 从可信 Workspace 解析内部键、过滤跨工作区样本并返回公开证据，
 * 不允许用户问题携带实体 ID 或改变仓储查询范围。</p>
 */
class InvestigationToolServiceTest {

    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final IssueCatalogRepository catalogRepository = mock(IssueCatalogRepository.class);
    private final IssueMetricBucketRepository metricRepository = mock(IssueMetricBucketRepository.class);
    private final AlertRepository alertRepository = mock(AlertRepository.class);
    private final CellIssueRepository cellIssueRepository = mock(CellIssueRepository.class);
    private final FeedbackEventRepository feedbackEventRepository = mock(FeedbackEventRepository.class);
    private final DashboardService dashboardService = mock(DashboardService.class);
    private final ExpressionRulesLoader expressionRulesLoader = expressionRulesLoader();
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-25T00:00:00Z"), ZoneOffset.UTC);

    /** 异常调查只能读取命中主题的当前 Workspace 指标、告警和脱敏样本。 */
    @Test
    void returnsWorkspaceScopedTrendAlertAndSanitizedSamplesForAnomalyPlan() throws Exception {
        UUID workspacePublicId = UUID.randomUUID();
        Workspace workspace = workspace(7L);
        IssueCatalog gameplay = catalog(10L, "bug_gameplay", "玩法Bug");
        FeedbackEvent allowed = feedbackEvent(7L, "更新后闪退");
        FeedbackEvent foreign = feedbackEvent(99L, "不应泄露的跨工作区样本");
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(catalogRepository.findByWorkspaceId(7L)).thenReturn(List.of(gameplay));
        when(metricRepository.findByWorkspaceIdAndBucketStartGreaterThanEqual(eq(7L), any()))
                .thenReturn(List.of(bucket(7L, 10L, "2026-07-20T00:00:00Z", 12), bucket(7L, 10L, "2026-07-24T00:00:00Z", 30)));
        when(alertRepository.findByWorkspaceIdAndIssueIdOrderByCreatedAtDesc(7L, 10L))
                .thenReturn(List.of(Alert.active(7L, 10L, 1L, OffsetDateTime.now(clock), 30, 12.0, 3.0, 6.0, 10, "[]")));
        when(cellIssueRepository.findByIssueId(10L)).thenReturn(List.of(
                CellIssue.of(7L, 1L, 10L, 1, "[101, 102]"),
                CellIssue.of(99L, 2L, 10L, 1, "[999]")));
        when(feedbackEventRepository.findById(101L)).thenReturn(Optional.of(allowed));
        when(feedbackEventRepository.findById(102L)).thenReturn(Optional.of(foreign));

        InvestigationResult result = service().investigate(
                workspacePublicId,
                "玩法Bug 为什么暴增？",
                new InvestigationPlanner(new InvestigationIntentDetector()).plan("玩法Bug 为什么暴增？"));

        assertThat(result.evidence()).extracting(InvestigationEvidence::tool).containsExactly(
                InvestigationToolType.ISSUE_TREND,
                InvestigationToolType.ALERT_HISTORY,
                InvestigationToolType.SAMPLE_FEEDBACK);
        assertThat(result.renderForPrompt()).contains("## 调查摘要").contains("玩法Bug").contains("更新后闪退")
                .doesNotContain("跨工作区").doesNotContain("issue_id");
        verify(metricRepository).findByWorkspaceIdAndBucketStartGreaterThanEqual(eq(7L), any());
        verify(alertRepository).findByWorkspaceIdAndIssueIdOrderByCreatedAtDesc(7L, 10L);
    }

    /** 历史只描述本次告警之前的事实；BOTH 不得为两个窗口重复生成相同历史。 */
    @Test
    void keepsAlertHistoryGlobalAndExcludesCurrentAndFutureAlerts() throws Exception {
        UUID workspacePublicId = UUID.randomUUID();
        Workspace workspace = workspace(7L);
        IssueCatalog gameplay = catalog(10L, "bug_gameplay", "玩法Bug");
        Alert previous = Alert.active(7L, 10L, 1L, OffsetDateTime.parse("2026-08-06T00:00:00Z"), 11, 2, 1, 3, 5, "[]");
        Alert current = Alert.active(7L, 10L, 1L, OffsetDateTime.parse("2026-08-08T00:00:00Z"), 80, 2, 1, 12, 5, "[]");
        Alert future = Alert.active(7L, 10L, 1L, OffsetDateTime.parse("2026-08-09T00:00:00Z"), 99, 2, 1, 15, 5, "[]");
        setField(previous, "id", 1L);
        setField(current, "id", 2L);
        setField(future, "id", 3L);
        setField(previous, "createdAt", OffsetDateTime.parse("2026-08-06T01:00:00Z"));
        setField(current, "createdAt", OffsetDateTime.parse("2026-08-08T01:00:00Z"));
        setField(future, "createdAt", OffsetDateTime.parse("2026-08-09T01:00:00Z"));
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(alertRepository.findByWorkspaceIdAndIssueIdOrderByCreatedAtDesc(7L, 10L))
                .thenReturn(List.of(future, current, previous));
        when(metricRepository.findByWorkspaceIdAndBucketStartGreaterThanEqual(eq(7L), any()))
                .thenReturn(List.of(bucket(7L, 10L, "2026-08-08T00:00:00Z", 80)));
        when(cellIssueRepository.findByIssueId(10L)).thenReturn(List.of());

        List<InvestigationEvidence> evidence = service().investigateForAlert(
                workspacePublicId,
                current,
                gameplay,
                new com.insightflow.investigation.window.InvestigationWindowResolver().resolve(
                        current.getBucketStart(), com.insightflow.investigation.window.InvestigationWindowSelection.BOTH));

        assertThat(evidence).filteredOn(item -> item.tool() == InvestigationToolType.ALERT_HISTORY)
                .singleElement()
                .satisfies(item -> assertThat(item.content()).contains("当前值 11").doesNotContain("当前值 80").doesNotContain("当前值 99"));
        assertThat(evidence).filteredOn(item -> item.tool() == InvestigationToolType.ISSUE_TREND)
                .allSatisfy(item -> assertThat(item.content()).contains("bucketStart=").contains("feedbackCount="));
        assertThat(evidence).filteredOn(item -> item.tool() == InvestigationToolType.PERIOD_COMPARISON)
                .allSatisfy(item -> assertThat(item.content()).contains("percentageChange=unavailable").contains("newActivity=true"));
    }

    /** 当前没有版本或活动事件来源时，版本比较必须以明确的不足证据结束，不能生成因果结论。 */
    @Test
    void reportsMissingVersionSourceInsteadOfInventingVersionComparison() throws Exception {
        UUID workspacePublicId = UUID.randomUUID();
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace(8L));
        when(catalogRepository.findByWorkspaceId(8L)).thenReturn(List.of());
        when(metricRepository.findByWorkspaceIdAndBucketStartGreaterThanEqual(eq(8L), any())).thenReturn(List.of());

        InvestigationResult result = service().investigate(
                workspacePublicId,
                "7月版本更新前后登录失败有什么变化？",
                new InvestigationPlanner(new InvestigationIntentDetector()).plan("7月版本更新前后登录失败有什么变化？"));

        assertThat(result.evidence()).anySatisfy(evidence -> {
            assertThat(evidence.tool()).isEqualTo(InvestigationToolType.DATA_AVAILABILITY);
            assertThat(evidence.sufficient()).isFalse();
            assertThat(evidence.content()).contains("未接入版本或活动事件数据");
        });
    }

    /** 表达层调查应返回 L2 五类分布，并在识别吐槽时给出 L2→L1 交叉分布。 */
    @Test
    void returnsExpressionDistributionAndDrilldownForComplaintQuestion() throws Exception {
        UUID workspacePublicId = UUID.randomUUID();
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace(7L));
        when(catalogRepository.findByWorkspaceId(7L)).thenReturn(List.of(catalog(10L, "bug_gameplay", "玩法Bug")));

        DashboardService.WindowInfo window = new DashboardService.WindowInfo(
                OffsetDateTime.parse("2026-07-18T00:00:00Z"),
                OffsetDateTime.parse("2026-07-25T00:00:00Z"));
        List<DashboardService.ExpressionCount> distribution = List.of(
                new DashboardService.ExpressionCount("expr_suggestion", "建议/诉求", 10),
                new DashboardService.ExpressionCount("expr_complaint", "吐槽/不满", 40),
                new DashboardService.ExpressionCount("expr_praise", "好评/推荐", 5),
                new DashboardService.ExpressionCount("expr_neutral", "体验分享", 8),
                new DashboardService.ExpressionCount("expr_other", "其他", 2));
        DashboardService.ExpressionSummary expressionSummary = new DashboardService.ExpressionSummary(
                distribution, List.of(), 0, "game-chaoziran", "v1");
        when(dashboardService.getDashboard(workspacePublicId, null, null)).thenReturn(
                new DashboardService.DashboardResponse(
                        null, window, List.of(), List.of(), null, null, expressionSummary));
        when(dashboardService.getExpressionTopics(workspacePublicId, "expr_complaint", null, null))
                .thenReturn(new DashboardService.ExpressionTopicsResponse(
                        "expr_complaint",
                        "game-chaoziran",
                        "v1",
                        List.of(new DashboardService.TopicCount(
                                UUID.randomUUID(), "bug_gameplay", "玩法Bug", 25)),
                        window));

        InvestigationResult result = service().investigate(
                workspacePublicId,
                "吐槽分布里玩法Bug占多少？",
                new InvestigationPlanner(new InvestigationIntentDetector()).plan("吐槽分布里玩法Bug占多少？"));

        assertThat(result.evidence()).extracting(InvestigationEvidence::tool).containsExactly(
                InvestigationToolType.EXPRESSION_DISTRIBUTION,
                InvestigationToolType.EXPRESSION_TOPIC_DRILLDOWN,
                InvestigationToolType.EXPRESSION_TOPIC_SAMPLES);
        assertThat(result.renderForPrompt())
                .contains("L2 表达分布")
                .contains("吐槽/不满 40 条")
                .contains("L2→L1 交叉分布")
                .contains("玩法Bug 25 条");
        verify(dashboardService).getDashboard(workspacePublicId, null, null);
        verify(dashboardService).getExpressionTopics(workspacePublicId, "expr_complaint", null, null);
    }

    /** L2×L1 交叉样本在识别表达类型与主题后返回脱敏文本，且不暴露内部主键。 */
    @Test
    void returnsExpressionTopicSamplesWhenBothDimensionsResolved() throws Exception {
        UUID workspacePublicId = UUID.randomUUID();
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace(7L));
        when(catalogRepository.findByWorkspaceId(7L)).thenReturn(List.of(catalog(10L, "bug_gameplay", "玩法Bug")));

        DashboardService.WindowInfo window = new DashboardService.WindowInfo(
                OffsetDateTime.parse("2026-07-18T00:00:00Z"),
                OffsetDateTime.parse("2026-07-25T00:00:00Z"));
        DashboardService.ExpressionSummary expressionSummary = new DashboardService.ExpressionSummary(
                List.of(new DashboardService.ExpressionCount("expr_complaint", "吐槽/不满", 5)),
                List.of(),
                0,
                "game-chaoziran",
                "v1");
        when(dashboardService.getDashboard(workspacePublicId, null, null)).thenReturn(
                new DashboardService.DashboardResponse(
                        null, window, List.of(), List.of(), null, null, expressionSummary));
        when(dashboardService.getExpressionTopics(workspacePublicId, "expr_complaint", null, null))
                .thenReturn(new DashboardService.ExpressionTopicsResponse(
                        "expr_complaint", "game-chaoziran", "v1", List.of(), window));
        when(dashboardService.getExpressionTopicSamples(
                        workspacePublicId, "expr_complaint", "bug_gameplay", null, null))
                .thenReturn(List.of(new DashboardService.FeedbackSample(
                        "更新后闪退太频繁", OffsetDateTime.now(clock), "ticket")));

        InvestigationResult result = service().investigate(
                workspacePublicId,
                "吐槽里玩法Bug有哪些反馈样本？",
                new InvestigationPlanner(new InvestigationIntentDetector()).plan("吐槽里玩法Bug有哪些反馈样本？"));

        assertThat(result.evidence()).anySatisfy(evidence -> {
            assertThat(evidence.tool()).isEqualTo(InvestigationToolType.EXPRESSION_TOPIC_SAMPLES);
            assertThat(evidence.sufficient()).isTrue();
            assertThat(evidence.content()).contains("更新后闪退");
        });
    }

    /** 每个测试使用独立服务实例，防止工作区数据通过可变成员意外残留。 */
    private InvestigationToolService service() {
        return new InvestigationToolService(
                workspaceService,
                catalogRepository,
                metricRepository,
                alertRepository,
                cellIssueRepository,
                feedbackEventRepository,
                dashboardService,
                expressionRulesLoader,
                new ObjectMapper(),
                clock);
    }

    /** 加载真实平台 L2 规则，使 resolveExpressionKey 与生产口径一致。 */
    private static ExpressionRulesLoader expressionRulesLoader() {
        ExpressionRulesLoader loader = new ExpressionRulesLoader();
        loader.load();
        return loader;
    }

    /** Workspace 的内部键只由服务端解析，测试不将其交给 Tool 外部输入。 */
    private Workspace workspace(long id) throws Exception {
        Workspace workspace = new Workspace("test", 1L);
        setField(workspace, "id", id);
        return workspace;
    }

    /** 主题目录为当前工作区创建，再在测试中补充内部键模拟已落库实体。 */
    private IssueCatalog catalog(long id, String key, String name) throws Exception {
        IssueCatalog catalog = IssueCatalog.create(7L, key, name);
        setField(catalog, "id", id);
        return catalog;
    }

    /** 日指标保留 UTC 时间，便于固定时钟下验证窗口查询。 */
    private IssueMetricBucket bucket(long workspaceId, long issueId, String start, int count) {
        return IssueMetricBucket.of(workspaceId, issueId, OffsetDateTime.parse(start), count, "{}", 1L);
    }

    /** 反馈事件只构造已脱敏文本；跨工作区事件用于验证二次过滤。 */
    private FeedbackEvent feedbackEvent(long workspaceId, String sanitizedText) {
        return FeedbackEvent.active(workspaceId, 1L, "hash", OffsetDateTime.now(clock), "工单", sanitizedText, sanitizedText, "{}", "content", 1L);
    }

    /** 仅在测试中回填 JPA 生成的内部键。 */
    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
