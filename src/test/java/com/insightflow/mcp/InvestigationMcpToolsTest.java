package com.insightflow.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.insightflow.agent.investigation.InvestigationIntent;
import com.insightflow.agent.investigation.InvestigationPlan;
import com.insightflow.agent.investigation.InvestigationResult;
import com.insightflow.agent.investigation.InvestigationToolService;
import com.insightflow.agent.investigation.InvestigationToolType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** MCP 调查 Tool 委托与 workspace UUID 解析边界。 */
@ExtendWith(MockitoExtension.class)
class InvestigationMcpToolsTest {

    @Mock
    private InvestigationToolService investigationToolService;

    @InjectMocks
    private InvestigationMcpTools mcpTools;

    @Test
    void rejectsInvalidWorkspaceUuid() {
        assertThatThrownBy(() -> mcpTools.issueTrend("not-a-uuid", "登录异常"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delegatesToInvestigationServiceWithResolvedWorkspace() {
        UUID workspaceId = UUID.randomUUID();
        InvestigationPlan plan = new InvestigationPlan(InvestigationIntent.TREND_EXPLANATION, List.of(InvestigationToolType.ISSUE_TREND));
        InvestigationResult result = new InvestigationResult(plan, List.of());
        when(investigationToolService.investigate(eq(workspaceId), eq("登录异常"), any(InvestigationPlan.class)))
                .thenReturn(result);

        String rendered = mcpTools.issueTrend(workspaceId.toString(), "登录异常");

        assertThat(rendered).contains("## 调查计划");
    }
}
