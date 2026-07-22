package com.insightflow.service.analysis;

import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.FeedbackEvent;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.FeedbackEventRepository;
import com.insightflow.repository.ProjectionFileRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 按 projection_file 反查导入任务与脱敏事件，作为 DataCellBuilder 切分的输入。
 *
 * <p>关联链：projection_file → import_file_id → AsyncTask(importFileId,type=import).id
 * → feedback_event.ingested_task_id。这条链是 InsightFlow 的可追溯边界——
 * PG 不保留 CSV 原文或 PII，只通过内部主键定位已脱敏的 sanitized_text。
 * Worker 因此可以离线重建某次投影的事件序列而无需触碰对象存储。</p>
 *
 * <p>投影可能由多份文件合并（一次 Task 包含多份 CSV 导入），多份 projection_file
 * 指向同一 AsyncTask 时，用 Set&lt;Long&gt; 去重 taskIds，避免同一任务被重复查询；
 * taskIds 为空说明该投影尚未完成任何导入，直接返回空列表。</p>
 *
 * <p>返回结果按 occurred_at 升序，满足 DataCellBuilder 对输入已排序的契约——
 * 切窗逻辑只做单遍扫描，不自行重排序。</p>
 *
 * <p>只读取 id / occurred_at / sanitizedText 三字段——这是投影计算所需的最小集，
 * 不引入 persistence 细节；归一化在此处完成，下游 IssueClassifier 与
 * RuleFirstIssueClassifier 直接消费 normalizedText，无需感知归一映射。</p>
 */
@Component
public class ProjectionSourceLoader {

    /** 来源文件仓储，定位投影冻结的 import_file。 */
    private final ProjectionFileRepository projectionFileRepository;
    /** 任务仓储，反查每份文件对应的导入任务 id。 */
    private final AsyncTaskRepository taskRepository;
    /** 事件仓储，按 ingestedTaskId 批量读取脱敏事件。 */
    private final FeedbackEventRepository eventRepository;
    /** 归一器，把 sanitized_text 转为分类用的稳定文本。 */
    private final IssueTextNormalizer normalizer;

    /** 构造加载器；归一器来自 IssueRulesLoader 的归一映射。 */
    public ProjectionSourceLoader(ProjectionFileRepository projectionFileRepository,
                                  AsyncTaskRepository taskRepository,
                                  FeedbackEventRepository eventRepository,
                                  IssueTextNormalizer normalizer) {
        this.projectionFileRepository = projectionFileRepository;
        this.taskRepository = taskRepository;
        this.eventRepository = eventRepository;
        this.normalizer = normalizer;
    }

    /**
     * 读取并归一投影来源的全部事件，按 occurred_at 升序返回。
     *
     * <p>按 projection_file 反查 import_file_id → AsyncTask.id → feedback_event.ingested_task_id；
     * 多文件合并去重 taskIds，空集直接短路返回，避免下游 IN 空集语义歧义。
     * 归一化在此完成而非延后到分类器，避免每个下游消费者重复调用归一器。</p>
     */
    public List<EventInput> load(Long projectionId, Long workspaceId) {
        // Set 去重：多份 projection_file 可能指向同一 AsyncTask，重复 taskId 会导致事件重复读取
        Set<Long> taskIds = new HashSet<>();
        projectionFileRepository.findByWorkspaceProjectionIdAndWorkspaceId(projectionId, workspaceId)
                .forEach(pf -> taskRepository
                        .findFirstByWorkspaceIdAndImportFileIdOrderByCreatedAtDesc(workspaceId, pf.getImportFileId())
                        .map(AsyncTask::getId)
                        .ifPresent(taskIds::add));
        if (taskIds.isEmpty()) {
            return List.of();
        }
        // 按 occurred_at 升序：DataCellBuilder 契约要求输入已排序，切窗只单遍扫描不重排
        List<FeedbackEvent> events = eventRepository
                .findByWorkspaceIdAndIngestedTaskIdInOrderByOccurredAtAsc(workspaceId, taskIds);
        List<EventInput> inputs = new ArrayList<>(events.size());
        for (FeedbackEvent event : events) {
            // 只携带 id / occurred_at / normalizedText 三字段，不暴露 sanitizedText 原文以外字段
            inputs.add(new EventInput(event.getId(), event.getOccurredAt(),
                    event.getSourceKind(),
                    normalizer.normalize(event.getSanitizedText())));
        }
        return inputs;
    }
}
