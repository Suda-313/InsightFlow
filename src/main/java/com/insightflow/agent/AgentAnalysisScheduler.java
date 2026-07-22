package com.insightflow.agent;

import com.insightflow.agent.event.ProjectionCompletedEvent;
import com.insightflow.entity.DataCell;
import com.insightflow.repository.DataCellRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 监听投影完成事件，异步触发 CellAnalysisAgent。
 * 代理标记 enabled=false 时跳过，允许无 LLM 环境运行。
 */
@Component
public class AgentAnalysisScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentAnalysisScheduler.class);
    private final CellAnalysisAgent cellAnalysisAgent;
    private final DataCellRepository dataCellRepository;
    private final boolean enabled;

    public AgentAnalysisScheduler(CellAnalysisAgent cellAnalysisAgent,
                                  DataCellRepository dataCellRepository,
                                  @Value("${insightflow.agent.enabled:true}") boolean enabled) {
        this.cellAnalysisAgent = cellAnalysisAgent;
        this.dataCellRepository = dataCellRepository;
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
        for (DataCell cell : cells) {
            try {
                cellAnalysisAgent.analyze("cell_" + cell.getId());
            } catch (Exception e) {
                log.warn("Cell {} 分析失败: {}", cell.getId(), e.getMessage());
            }
        }
        log.info("投影 {} 的 Agent 分析完成，共 {} 个 Cell", event.getProjectionId(), cells.size());
    }
}