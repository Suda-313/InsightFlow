package com.insightflow.service.analysis;

import com.insightflow.entity.IssueBaselineProfile;
import com.insightflow.entity.IssueMetricBucket;
import com.insightflow.entity.Workspace;
import com.insightflow.entity.WorkspaceProjection;
import com.insightflow.repository.DataCellRepository;
import com.insightflow.repository.FeedbackProjectionAnnotationRepository;
import com.insightflow.repository.IssueMetricBucketRepository;
import com.insightflow.repository.WorkspaceProjectionRepository;
import com.insightflow.repository.WorkspaceRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单事务编排投影事实写入；幂等守卫防止重试重复累计，失败整体回滚不残留部分事实。
 *
 * <p>执行事务只写事实与 source window（projection 仍 running）；终态翻转交给 completion
 * 的独立短事务，避免计算异常回滚失败标记。</p>
 */
@Service
public class WorkspaceProjectionExecutionService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceProjectionExecutionService.class);

    /** 投影记录仓储，加载与记录 source window。 */
    private final WorkspaceProjectionRepository projectionRepository;
    /** Cell 仓储，幂等守卫判断事实是否已写。 */
    private final DataCellRepository dataCellRepository;
    /** 事件加载器。 */
    private final ProjectionSourceLoader sourceLoader;
    /** Cell 切分器。 */
    private final DataCellBuilder dataCellBuilder;
    /** 事实写入器。 */
    private final ProjectionFactWriter factWriter;
    /** 指标桶写入器，按日聚合分类结果。 */
    private final MetricBucketService metricBucketService;
    /** 日指标桶仓储，查询已写入的桶。 */
    private final IssueMetricBucketRepository metricBucketRepository;
    /** EWMA 基线服务，按日增量更新基线。 */
    private final EwmaBaselineService ewmaBaselineService;
    /** 告警检测器，根据基线与冷却期判断是否触发新告警。 */
    private final AlertDetector alertDetector;
    /** 平台 L2 表达分类器，与 L1 规则并行计算，互不影响彼此的候选与复核判定。 */
    private final ExpressionClassifier expressionClassifier;
    /** L2 规则加载器，提供冻结版本号写入标注行，供后续追溯统计口径变化。 */
    private final ExpressionRulesLoader expressionRulesLoader;
    /** Topic Pack 注册表：按 Workspace 绑定解析 L1 规则与 Pack 标识。 */
    private final TopicPackRegistry topicPackRegistry;
    /** Workspace 仓储，投影执行时读取 Pack 绑定。 */
    private final WorkspaceRepository workspaceRepository;
    /** L2 标注写入器，按事件逐条写 feedback_projection_annotation。 */
    private final ProjectionAnnotationWriter annotationWriter;
    /** L2 日指标桶写入器，供 Dashboard 首屏趋势查询。 */
    private final ExpressionMetricBucketService expressionMetricBucketService;
    /** L2 标注仓储，与 data_cell 共同构成投影完成判定。 */
    private final FeedbackProjectionAnnotationRepository feedbackProjectionAnnotationRepository;
    /** 半完成投影事实清理，避免仅有 L1 无 L2 时幂等守卫永久跳过。 */
    private final ProjectionFactWiper projectionFactWiper;
    /** Pack L1 编排：规则优先，可选 LLM 补标 topic_general 子集。 */
    private final PackTopicClassifier packTopicClassifier;

    /** 构造编排服务；所有依赖在调用方事务内执行。 */
    public WorkspaceProjectionExecutionService(WorkspaceProjectionRepository projectionRepository,
                                                DataCellRepository dataCellRepository,
                                                ProjectionSourceLoader sourceLoader,
                                                DataCellBuilder dataCellBuilder,
                                                ProjectionFactWriter factWriter,
                                                MetricBucketService metricBucketService,
                                                IssueMetricBucketRepository metricBucketRepository,
                                                EwmaBaselineService ewmaBaselineService,
                                                AlertDetector alertDetector,
                                                ExpressionClassifier expressionClassifier,
                                                ExpressionRulesLoader expressionRulesLoader,
                                                TopicPackRegistry topicPackRegistry,
                                                WorkspaceRepository workspaceRepository,
                                                ProjectionAnnotationWriter annotationWriter,
                                                ExpressionMetricBucketService expressionMetricBucketService,
                                                FeedbackProjectionAnnotationRepository feedbackProjectionAnnotationRepository,
                                                ProjectionFactWiper projectionFactWiper,
                                                PackTopicClassifier packTopicClassifier) {
        this.projectionRepository = projectionRepository;
        this.dataCellRepository = dataCellRepository;
        this.sourceLoader = sourceLoader;
        this.dataCellBuilder = dataCellBuilder;
        this.factWriter = factWriter;
        this.metricBucketService = metricBucketService;
        this.metricBucketRepository = metricBucketRepository;
        this.ewmaBaselineService = ewmaBaselineService;
        this.alertDetector = alertDetector;
        this.expressionClassifier = expressionClassifier;
        this.expressionRulesLoader = expressionRulesLoader;
        this.topicPackRegistry = topicPackRegistry;
        this.workspaceRepository = workspaceRepository;
        this.annotationWriter = annotationWriter;
        this.expressionMetricBucketService = expressionMetricBucketService;
        this.feedbackProjectionAnnotationRepository = feedbackProjectionAnnotationRepository;
        this.projectionFactWiper = projectionFactWiper;
        this.packTopicClassifier = packTopicClassifier;
    }

    /**
     * 执行投影事实写入；幂等守卫命中则跳过。返回是否有事件被处理。
     * 全部在 REQUIRES_NEW 事务内；抛异常整体回滚，调用方据此调 fail()。
     *
     * <p>REQUIRES_NEW 分离执行事务与 CompletionService 的终态翻转事务，执行异常回滚不影响
     * 完成标记（spec §3.2）。重试时事务独立，幂等守卫保证不重复写入。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean execute(Long projectionId, Long workspaceId) {
        WorkspaceProjection projection = projectionRepository.findById(projectionId)
                .orElseThrow(() -> new IllegalStateException("Projection not found: " + projectionId));
        // 幂等守卫：data_cell 与 L2 标注须同时存在；仅有 L1 的半完成投影（旧版 Worker）自动清事实后重跑。
        if (isProjectionComplete(projectionId, workspaceId)) {
            return true;
        }
        if (!dataCellRepository.findByWorkspaceProjectionIdAndWorkspaceId(projectionId, workspaceId).isEmpty()) {
            projectionFactWiper.wipeWorkspaceAnalysisFacts(workspaceId, projectionId);
        }
        // 加载原始事件；若无事件则直接返回 false，不写入任何事实
        List<EventInput> events = sourceLoader.load(projectionId, workspaceId);
        if (events.isEmpty()) {
            return false;
        }
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new IllegalStateException("Workspace not found: " + workspaceId));
        // L1 规则来自 Workspace 绑定的 Topic Pack（非全局 issue-rules.toml）；历史 link 的 canonical_key 不做 alias 映射。
        TopicPackLoader topicPack = topicPackRegistry.resolveForWorkspace(workspace);
        RuleFirstIssueClassifier classifier = new RuleFirstIssueClassifier(topicPack.rules());
        Map<String, String> canonicalNames = buildCanonicalNames(topicPack);
        // 逐事件分类：L1 按规则优先策略匹配，L2 平台表达分类并行计算，二者互不影响彼此的候选与复核判定。
        Map<Long, List<Classification>> classificationsByEventId = new HashMap<>();
        Map<Long, List<TopicSentiment>> sentimentsByEventId = new HashMap<>();
        Map<Long, String> reviewReasonsByEventId = new HashMap<>();
        Map<Long, ExpressionClassification> expressionsByEventId = new HashMap<>();
        Map<Long, TopicLlmAttempt> llmAttemptsByEventId = new HashMap<>();
        TopicSentimentAnalyzer sentimentAnalyzer = new TopicSentimentAnalyzer();
        for (EventInput event : events) {
            ExpressionClassification expression = expressionClassifier.classify(event.normalizedText());
            expressionsByEventId.put(event.id(), expression);
            PackTopicClassifier.PackTopicClassificationOutcome topicOutcome = packTopicClassifier.classify(
                    event.normalizedText(), expression, topicPack, classifier);
            List<Classification> classifications = topicOutcome.classifications();
            if (topicOutcome.reviewReason() != null) {
                reviewReasonsByEventId.put(event.id(), topicOutcome.reviewReason());
            }
            if (topicOutcome.llmAttempt() != null) {
                llmAttemptsByEventId.put(event.id(), topicOutcome.llmAttempt());
            }
            classificationsByEventId.put(event.id(), classifications);
            sentimentsByEventId.put(event.id(), sentimentAnalyzer.analyze(event.normalizedText(),
                    classifications.stream().map(Classification::canonicalKey).toList()));
        }
        // 按 40/60/6000 守卫切分 DataCell
        List<DataCellPlan> cells = dataCellBuilder.split(events);
        // 写入事实：cell + 分类 + 规则名；同一事务内，失败整体回滚
        factWriter.write(projectionId, workspaceId, cells, classificationsByEventId, sentimentsByEventId,
                reviewReasonsByEventId, canonicalNames);
        // 按日聚合分类结果写入指标桶，用于 dashboard 趋势分析
        metricBucketService.write(projectionId, workspaceId, events, classificationsByEventId, canonicalNames);
        // 写入 L2 标注行：每事件恰好 1 行，独立于 DataCell 切分，冻结本次投影的规则与 Pack 版本
        annotationWriter.write(projectionId, workspaceId, events, expressionsByEventId,
                expressionRulesLoader.currentVersion(), topicPack.packId(), topicPack.packVersion(),
                llmAttemptsByEventId);
        long l2Count = feedbackProjectionAnnotationRepository
                .countByWorkspaceProjectionIdAndWorkspaceId(projectionId, workspaceId);
        log.info(
                "投影 {} 工作区 {} 完成 L1/L2 写入：events={} cells={} l2Annotations={} pack={}",
                projectionId,
                workspaceId,
                events.size(),
                cells.size(),
                l2Count,
                topicPack.packId());
        if (l2Count == 0) {
            throw new IllegalStateException("L2 annotation write produced zero rows for projection " + projectionId);
        }
        // 按日聚合 L2 分类结果写入指标桶，用于 Dashboard 首屏趋势
        expressionMetricBucketService.write(projectionId, workspaceId, events, expressionsByEventId);
        // 查询本次投影写入的日指标桶，更新基线并检测告警
        List<IssueMetricBucket> buckets = metricBucketRepository
                .findByWorkspaceProjectionIdAndWorkspaceId(projectionId, workspaceId);
        for (IssueMetricBucket bucket : buckets) {
            IssueBaselineProfile profile = ewmaBaselineService.update(
                    workspaceId, bucket.getIssueId(), bucket.getBucketStart(), bucket.getFeedbackCount());
            alertDetector.detect(workspaceId, bucket.getIssueId(), projectionId,
                    bucket.getBucketStart(), bucket.getFeedbackCount(), profile);
        }
        // 记录源时间窗口，用于后续增量计算边界
        projection.recordSourceWindow(cells.get(0).windowStart(),
                cells.get(cells.size() - 1).windowEnd());
        // saveAndFlush 强制刷入 REQUIRES_NEW 事务，确保 CompletionService 独立事务读到最新 window
        projectionRepository.saveAndFlush(projection);
        return true;
    }

    /** 投影完成 = 同一 projection 下既有 data_cell 又有 L2 标注行。 */
    private boolean isProjectionComplete(Long projectionId, Long workspaceId) {
        boolean hasCells = !dataCellRepository.findByWorkspaceProjectionIdAndWorkspaceId(projectionId, workspaceId).isEmpty();
        long annotationCount = feedbackProjectionAnnotationRepository
                .countByWorkspaceProjectionIdAndWorkspaceId(projectionId, workspaceId);
        return hasCells && annotationCount > 0;
    }

    /**
     * 从 Pack 规则与 catalog 构建 canonical_key→展示名映射；catalog 优先（含 topic_general）。
     */
    private Map<String, String> buildCanonicalNames(TopicPackLoader topicPack) {
        Map<String, String> canonicalNames = new HashMap<>();
        topicPack.rules().forEach(rule -> canonicalNames.put(rule.canonicalKey(), rule.name()));
        topicPack.topics().forEach(topic -> canonicalNames.putIfAbsent(topic.canonicalKey(), topic.name()));
        canonicalNames.putIfAbsent(TopicPackDefaults.TOPIC_GENERAL_KEY, TopicPackDefaults.TOPIC_GENERAL_NAME);
        return canonicalNames;
    }
}
