package com.insightflow.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.agent.dto.CellInsight;
import com.insightflow.agent.event.ProjectionCompletedEvent;
import com.insightflow.config.AgentApiKeyPresentCondition;
import com.insightflow.entity.CellIssue;
import com.insightflow.entity.DataCell;
import com.insightflow.entity.FeedbackEvent;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.CellIssueRepository;
import com.insightflow.repository.DataCellRepository;
import com.insightflow.repository.FeedbackEventRepository;
import com.insightflow.repository.WorkspaceRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 监听投影完成事件，异步触发 CellAnalysisAgent。
 * 从 Cell 内提取脱敏反馈文本，拼接后传给 Agent 做 LLM 分析。
 */
@Component
@Conditional(AgentApiKeyPresentCondition.class)
public class AgentAnalysisScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentAnalysisScheduler.class);
    private static final int MAX_CELLS_TO_ANALYZE = 5;
    private static final int MAX_TEXT_LENGTH = 4000;

    private final CellAnalysisAgent cellAnalysisAgent;
    private final DataCellRepository dataCellRepository;
    private final CellIssueRepository cellIssueRepository;
    private final FeedbackEventRepository feedbackEventRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public AgentAnalysisScheduler(CellAnalysisAgent cellAnalysisAgent,
                                  DataCellRepository dataCellRepository,
                                  CellIssueRepository cellIssueRepository,
                                  FeedbackEventRepository feedbackEventRepository,
                                  WorkspaceRepository workspaceRepository,
                                  ObjectMapper objectMapper,
                                  @Value("${insightflow.agent.enabled:true}") boolean enabled) {
        this.cellAnalysisAgent = cellAnalysisAgent;
        this.dataCellRepository = dataCellRepository;
        this.cellIssueRepository = cellIssueRepository;
        this.feedbackEventRepository = feedbackEventRepository;
        this.workspaceRepository = workspaceRepository;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    @Async
    @EventListener
    public void onProjectionCompleted(ProjectionCompletedEvent event) {
        if (!enabled) {
            log.info("Agent 分析跳过: projection_id={}, workspace_id={}, reason=disabled",
                    event.getProjectionId(), event.getWorkspaceId());
            return;
        }
        Long projectionId = Long.valueOf(event.getProjectionId());
        Long workspaceId = Long.valueOf(event.getWorkspaceId());
        Workspace workspace = workspaceRepository.findById(workspaceId).orElse(null);
        if (workspace == null) {
            log.warn("Agent 分析跳过: projection_id={}, workspace_id={}, reason=workspace_not_found",
                    event.getProjectionId(), workspaceId);
            return;
        }
        List<DataCell> cells = dataCellRepository.findByWorkspaceProjectionIdAndWorkspaceId(projectionId, workspaceId);
        // 只记录投影和 Cell 数量，方便定位事件是否送达，同时不把反馈样本或模型输入写入日志。
        log.info("Agent 分析开始: projection_id={}, workspace_id={}, data_cell_count={}",
                projectionId, workspaceId, cells.size());
        int analyzed = 0;
        for (DataCell cell : cells) {
            if (analyzed >= MAX_CELLS_TO_ANALYZE) break;
            try {
                String cellText = buildCellText(cell.getId());
                if (cellText.isEmpty()) {
                    log.info("Cell Agent 分析跳过: projection_id={}, cell_id={}, reason=no_sample_text",
                            projectionId, cell.getId());
                    continue;
                }
                log.info("Cell Agent 分析开始: projection_id={}, cell_id={}, input_chars={}",
                        projectionId, cell.getId(), cellText.length());
                CellInsight insight = cellAnalysisAgent.analyze(workspace.getPublicId(), cellText);
                analyzed++;
                log.info("Cell Agent 分析完成: projection_id={}, cell_id={}, classification={}, sentiment={}, risk={}",
                        projectionId,
                        cell.getId(),
                        insight.classification() != null ? insight.classification().canonicalKey() : "N/A",
                        insight.sentiment() != null ? insight.sentiment().sentiment() : "N/A",
                        insight.risk() != null ? insight.risk().riskLevel() : "N/A");
            } catch (Exception e) {
                // 异常消息可能来自模型或输入解析，不可直接记录；保留类型即可用于排障归类。
                log.warn("Cell Agent 分析失败: projection_id={}, cell_id={}, exception_type={}",
                        projectionId, cell.getId(), e.getClass().getSimpleName());
            }
        }
        log.info("Agent 分析完成: projection_id={}, workspace_id={}, analyzed_cell_count={}, total_cell_count={}",
                projectionId, workspaceId, analyzed, cells.size());
    }

    /** 从 Cell 的 cell_issue 记录中提取脱敏反馈文本，拼接为 Agent 输入。 */
    private String buildCellText(Long dataCellId) {
        try {
            List<CellIssue> cellIssues = cellIssueRepository.findByDataCellId(dataCellId);
            Set<Long> eventIds = new HashSet<>();
            for (CellIssue ci : cellIssues) {
                try {
                    List<Long> ids = objectMapper.readValue(ci.getSampleEventIdsJson(),
                            new TypeReference<List<Long>>() {});
                    eventIds.addAll(ids);
                } catch (Exception ignored) {}
            }
            if (eventIds.isEmpty()) return "";
            List<FeedbackEvent> events = feedbackEventRepository.findAllById(eventIds);
            StringBuilder sb = new StringBuilder();
            for (FeedbackEvent e : events) {
                String text = e.getSanitizedText();
                if (text != null && !text.isBlank()) {
                    if (sb.length() + text.length() > MAX_TEXT_LENGTH) break;
                    sb.append(text).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("构建 Cell {} 文本失败: {}", dataCellId, e.getMessage());
            return "";
        }
    }
}
