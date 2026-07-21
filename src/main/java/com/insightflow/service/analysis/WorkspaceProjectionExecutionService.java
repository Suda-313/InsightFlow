package com.insightflow.service.analysis;

import com.insightflow.entity.WorkspaceProjection;
import com.insightflow.repository.DataCellRepository;
import com.insightflow.repository.WorkspaceProjectionRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /** 投影记录仓储，加载与记录 source window。 */
    private final WorkspaceProjectionRepository projectionRepository;
    /** Cell 仓储，幂等守卫判断事实是否已写。 */
    private final DataCellRepository dataCellRepository;
    /** 事件加载器。 */
    private final ProjectionSourceLoader sourceLoader;
    /** 规则优先分类器。 */
    private final RuleFirstIssueClassifier classifier;
    /** Cell 切分器。 */
    private final DataCellBuilder dataCellBuilder;
    /** 事实写入器。 */
    private final ProjectionFactWriter factWriter;
    /** 规则加载器，提供 canonical name 映射。 */
    private final IssueRulesLoader rulesLoader;

    /** 构造编排服务；所有依赖在调用方事务内执行。 */
    public WorkspaceProjectionExecutionService(WorkspaceProjectionRepository projectionRepository,
                                                DataCellRepository dataCellRepository,
                                                ProjectionSourceLoader sourceLoader,
                                                RuleFirstIssueClassifier classifier,
                                                DataCellBuilder dataCellBuilder,
                                                ProjectionFactWriter factWriter,
                                                IssueRulesLoader rulesLoader) {
        this.projectionRepository = projectionRepository;
        this.dataCellRepository = dataCellRepository;
        this.sourceLoader = sourceLoader;
        this.classifier = classifier;
        this.dataCellBuilder = dataCellBuilder;
        this.factWriter = factWriter;
        this.rulesLoader = rulesLoader;
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
        // 幂等守卫前置：data_cell 存在 = 事实已写，避免重试重复加载源数据与重复写入
        if (!dataCellRepository.findByWorkspaceProjectionIdAndWorkspaceId(projectionId, workspaceId).isEmpty()) {
            return true;
        }
        // 加载原始事件；若无事件则直接返回 false，不写入任何事实
        List<EventInput> events = sourceLoader.load(projectionId, workspaceId);
        if (events.isEmpty()) {
            return false;
        }
        // 逐事件分类：按规则优先策略匹配，输出每事件的分类结果
        Map<Long, List<Classification>> classificationsByEventId = new HashMap<>();
        for (EventInput event : events) {
            classificationsByEventId.put(event.id(), classifier.classify(event.normalizedText()));
        }
        // 按 40/60/6000 守卫切分 DataCell
        List<DataCellPlan> cells = dataCellBuilder.split(events);
        // 提取规则 canonical name 映射，供事实写入器关联规则名称
        Map<String, String> canonicalNames = new HashMap<>();
        rulesLoader.rules().forEach(r -> canonicalNames.put(r.canonicalKey(), r.name()));
        // 写入事实：cell + 分类 + 规则名；同一事务内，失败整体回滚
        factWriter.write(projectionId, workspaceId, cells, classificationsByEventId, canonicalNames);
        // 记录源时间窗口，用于后续增量计算边界
        projection.recordSourceWindow(cells.get(0).windowStart(),
                cells.get(cells.size() - 1).windowEnd());
        // saveAndFlush 强制刷入 REQUIRES_NEW 事务，确保 CompletionService 独立事务读到最新 window
        projectionRepository.saveAndFlush(projection);
        return true;
    }
}
