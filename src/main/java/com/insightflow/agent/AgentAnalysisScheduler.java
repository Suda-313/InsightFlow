package com.insightflow.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.agent.dto.CellInsight;
import com.insightflow.agent.event.ProjectionCompletedEvent;
import com.insightflow.entity.CellIssue;
import com.insightflow.entity.DataCell;
import com.insightflow.entity.FeedbackEvent;
import com.insightflow.repository.CellIssueRepository;
import com.insightflow.repository.DataCellRepository;
import com.insightflow.repository.FeedbackEventRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 监听投影完成事件，异步触发 CellAnalysisAgent。
 * 从 Cell 内提取脱敏反馈文本，拼接后传给 Agent 做 LLM 分析。
 */
@Component
public class AgentAnalysisScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentAnalysisScheduler.class);
    private static final int MAX_CELLS_TO_ANALYZE = 5;
    private static final int MAX_TEXT_LENGTH = 4000;

    private final CellAnalysisAgent cellAnalysisAgent;
    private final DataCellRepository dataCellRepository;
    private final CellIssueRepository cellIssueRepository;
    private final FeedbackEventRepository feedbackEventRepository;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public AgentAnalysisScheduler(CellAnalysisAgent cellAnalysisAgent,
                                  DataCellRepository dataCellRepository,
                                  CellIssueRepository cellIssueRepository,
                                  FeedbackEventRepository feedbackEventRepository,
                                  ObjectMapper objectMapper,
                                  @Value("${insightflow.agent.enabled:true}") boolean enabled) {
        this.cellAnalysisAgent = cellAnalysisAgent;
        this.dataCellRepository = dataCellRepository;
        this.cellIssueRepository = cellIssueRepository;
        this.feedbackEventRepository = feedbackEventRepository;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    @Async
    @EventListener
    public void onProjectionCompleted(ProjectionCompletedEvent event) {
        if (!enabled) {
            log.info("Agent 已禁用，跳过投影 {} 的分析", event.getProjectionId());
            return;
        }
        log.info("开始 Agent 分析投影 {}", event.getProjectionId());
        Long projectionId = Long.valueOf(event.getProjectionId());
        Long workspaceId = Long.valueOf(event.getWorkspaceId());
        List<DataCell> cells = dataCellRepository.findByWorkspaceProjectionIdAndWorkspaceId(projectionId, workspaceId);
        int analyzed = 0;
        for (DataCell cell : cells) {
            if (analyzed >= MAX_CELLS_TO_ANALYZE) break;
            try {
                String cellText = buildCellText(cell.getId());
                if (cellText.isEmpty()) continue;
                CellInsight insight = cellAnalysisAgent.analyze(cellText);
                analyzed++;
                log.info("Cell {} Agent 分析结果: classification={}, sentiment={}, risk={}",
                        cell.getId(),
                        insight.classification() != null ? insight.classification().canonicalKey() : "N/A",
                        insight.sentiment() != null ? insight.sentiment().sentiment() : "N/A",
                        insight.risk() != null ? insight.risk().riskLevel() : "N/A");
            } catch (Exception e) {
                log.warn("Cell {} 分析失败: {}", cell.getId(), e.getMessage());
            }
        }
        log.info("投影 {} 的 Agent 分析完成，已分析 {}/{} 个 Cell", event.getProjectionId(), analyzed, cells.size());
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