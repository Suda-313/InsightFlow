package com.insightflow.mcp;

import com.insightflow.knowledge.KnowledgeRetrievalResult;
import com.insightflow.knowledge.KnowledgeSearchTool;
import java.util.UUID;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 企业知识库只读检索 MCP 入口。
 *
 * <p>不经 {@code InvestigationToolService} 的 KNOWLEDGE_SEARCH 分支，直接调用 {@link KnowledgeSearchTool}，
 * 与聊天链路一致并保留 workspace 隔离。</p>
 */
@Component
@ConditionalOnProperty(name = "insightflow.mcp.enabled", havingValue = "true")
public class KnowledgeMcpTool {

    private final KnowledgeSearchTool knowledgeSearchTool;

    public KnowledgeMcpTool(KnowledgeSearchTool knowledgeSearchTool) {
        this.knowledgeSearchTool = knowledgeSearchTool;
    }

    @Tool(
            name = "insightflow_knowledge_search",
            description = "只读：按 workspace 隔离混合检索已发布企业知识，返回 Top8 证据片段与来源链接。")
    public String knowledgeSearch(
            @ToolParam(description = "Workspace 公开 UUID") String workspacePublicId,
            @ToolParam(description = "自然语言问题") String question) {
        UUID workspaceId = UUID.fromString(workspacePublicId.trim());
        KnowledgeRetrievalResult result = knowledgeSearchTool.retrieve(workspaceId, question);
        return result.renderForPrompt();
    }
}
