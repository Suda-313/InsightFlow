package com.insightflow.service.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.CellIssue;
import com.insightflow.entity.DataCell;
import com.insightflow.entity.FeedbackIssueLink;
import com.insightflow.repository.CellIssueRepository;
import com.insightflow.repository.DataCellRepository;
import com.insightflow.repository.FeedbackIssueLinkRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 在执行事务内写 feedback_issue_link / data_cell / cell_issue；任一步失败由外层事务回滚。
 *
 * <p>事实写入按 Cell → 事件 → 分类三重循环展开：先 saveAndFlush DataCell 取回 id
 * （CellIssue 需要 data_cell_id 外键，必须同事务内可见），再按事件遍历其 0..2
 * 个 Classification，调用 IssueCatalogService 把 canonical_key 解析为 issue_id，
 * 写一条 feedback_issue_link，同时在内存 byIssue 聚合计数与样本 id。
 * 最后按 Cell 内每主题 flush 一条 cell_issue。</p>
 *
 * <p>幂等性依赖唯一约束（feedback_issue_link 上 (projection_id, event_id, issue_id)
 * 与 cell_issue 上 (data_cell_id, issue_id)）兜底——重试时重复 INSERT 由约束阻断，
 * 不需要本类自行判重。</p>
 *
 * <p>边界：feedback_issue_link 只存 issue_id + confidence + assignment_method，
 * 不存原文或归一文本；cell_issue.sample_event_ids 只存内部 BIGINT id 的 JSON
 * 数组，每主题每 Cell 最多 5 条——是证据样本而非全集，避免在计数表里膨胀。
 * JSON 失败抛 IllegalStateException 让调用方事务回滚，拒绝写半条事实。</p>
 */
@Component
public class ProjectionFactWriter {

    /** 关联仓储，按事件×主题写 link。 */
    private final FeedbackIssueLinkRepository linkRepository;
    /** Cell 仓储，按 projection 写 data_cell。 */
    private final DataCellRepository dataCellRepository;
    /** Cell-主题计数仓储。 */
    private final CellIssueRepository cellIssueRepository;
    /** JSON 工具，序列化 sample_event_ids。 */
    private final ObjectMapper objectMapper;
    /** 主题目录服务，把 canonical_key 解析为 issue_id。 */
    private final IssueCatalogService issueCatalogService;

    /** 构造事实写入器；所有写入在调用方事务内完成。 */
    public ProjectionFactWriter(FeedbackIssueLinkRepository linkRepository,
                                DataCellRepository dataCellRepository,
                                CellIssueRepository cellIssueRepository,
                                ObjectMapper objectMapper,
                                IssueCatalogService issueCatalogService) {
        this.linkRepository = linkRepository;
        this.dataCellRepository = dataCellRepository;
        this.cellIssueRepository = cellIssueRepository;
        this.objectMapper = objectMapper;
        this.issueCatalogService = issueCatalogService;
    }

    /**
     * 写全部事实；classificationsByEventId 给每条事件的 0..2 个分类结果。
     * cellPlans 已切分完成，writer 按 Cell 聚合主题计数。
     *
     * <p>每个 Cell 先 saveAndFlush DataCell（拿到 IDENTITY 生成的 id）再写
     * CellIssue；同一调用方事务内所有 saveAndFlush 共享连接与可见性。事件×分类
     * 展开为 link 行，主题×Cell 聚合为 cell_issue 行——这是星型事实表的物理形态。</p>
     */
    public void write(Long projectionId, Long workspaceId,
                      List<DataCellPlan> cellPlans,
                      Map<Long, List<Classification>> classificationsByEventId,
                      Map<String, String> canonicalNames) {
        for (DataCellPlan plan : cellPlans) {
            // saveAndFlush 而非 save：必须立即拿到 cell.id 才能在同事务写 CellIssue（data_cell_id 外键）
            DataCell cell = dataCellRepository.saveAndFlush(DataCell.of(
                    workspaceId, projectionId, plan.windowStart(), plan.windowEnd(),
                    plan.closeReason(), plan.events().size(), plan.estimatedTokens()));
            Map<Long, CellAggregator> byIssue = new HashMap<>();
            for (EventInput event : plan.events()) {
                // 缺失 event.id 表示该事件未被分类，按 0 分类处理（不产生 link，不计入 cell_issue）
                List<Classification> classifications = classificationsByEventId.getOrDefault(event.id(), List.of());
                for (Classification c : classifications) {
                    // findOrCreate 保证 (workspaceId, canonicalKey) 唯一，getId() 此刻非空
                    Long issueId = issueCatalogService.findOrCreate(
                            workspaceId, c.canonicalKey(), canonicalNames.get(c.canonicalKey())).getId();
                    // 规则别名只记一次，不参与计数；追溯用
                    issueCatalogService.recordAliasIfNeeded(workspaceId, issueId, canonicalNames.get(c.canonicalKey()));
                    // link 只存 issue_id + confidence + assignment_method，绝不存原文
                    linkRepository.saveAndFlush(FeedbackIssueLink.active(
                            workspaceId, event.id(), issueId, projectionId, c.assignmentMethod(), c.confidence()));
                    // 内存聚合；写库推迟到本 Cell 末尾，避免每事件重复查 cell_issue
                    byIssue.computeIfAbsent(issueId, k -> new CellAggregator()).add(event.id());
                }
            }
            for (Map.Entry<Long, CellAggregator> entry : byIssue.entrySet()) {
                // sample_event_ids 是 JSON 数组字符串，列定义为 jsonb；只存 BIGINT id，绝不存文本
                cellIssueRepository.saveAndFlush(CellIssue.of(
                        workspaceId, cell.getId(), entry.getKey(),
                        entry.getValue().count, toJson(entry.getValue().samples)));
            }
        }
    }

    /**
     * 内部聚合：每 Cell 每主题的计数与最多 5 条样本。
     *
     * <p>样本上限 5 是证据回溯的预算——足以人工抽检一条主题是否被错误归类，
     * 但不把整段事件 id 塞进 cell_issue 导致膨胀。</p>
     */
    private static final class CellAggregator {
        int count;
        final List<Long> samples = new ArrayList<>();
        void add(Long eventId) {
            count++;
            if (samples.size() < 5) {
                samples.add(eventId);
            }
        }
    }

    /**
     * 把样本 id 列表序列化为 JSON 数组字符串；失败抛 IllegalStateException 让事务回滚。
     *
     * <p>JsonProcessingException 是可恢复的编程错误而非业务异常，但仍然必须
     * 让事务回滚——已写的 link / cell_issue 都应回滚，绝不留半条事实。</p>
     */
    private String toJson(List<Long> ids) {
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize sample_event_ids", e);
        }
    }
}
