package com.insightflow.agent.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.agent.dto.ReconciliationReport;
import com.insightflow.agent.dto.ReportDraft;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/**
 * ReportAgent 单元测试。
 */
class ReportAgentTest {

    private final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    private final ReconciliationEngine reconciliationEngine = new ReconciliationEngine();
    private final ReportTools reportTools = mock(ReportTools.class);

    private final ReportAgent reportAgent = new ReportAgent(chatClient, reconciliationEngine, reportTools);

    @Test
    void returnsDraftImmediatelyWhenReconciliationPasses() {
        ReportDraft draft = new ReportDraft(
                "本周共 100 条工单。",
                List.of("highlight"),
                List.of("recommendation"),
                List.of());

        when(chatClient.prompt()
                .system(anyString())
                .user(anyString())
                .call()
                .entity(ReportDraft.class))
                .thenReturn(draft);

        MergedData mergedData = new MergedData("summary", 100, Map.of());
        ReportResult result = reportAgent.generate(mergedData);

        assertThat(result).isNotNull();
        assertThat(result.draft()).isEqualTo(draft);
        assertThat(result.reconciliation().ok()).isTrue();
        verify(chatClient.prompt().system(anyString()).user(anyString()).call(), times(1))
                .entity(ReportDraft.class);
    }

    @Test
    void revisesDraftWhenReconciliationFails() {
        ReportDraft badDraft = new ReportDraft(
                "本周共 99 条工单。",
                List.of("highlight"),
                List.of("recommendation"),
                List.of());

        ReportDraft goodDraft = new ReportDraft(
                "本周共 100 条工单。",
                List.of("highlight"),
                List.of("recommendation"),
                List.of());

        when(chatClient.prompt()
                .system(anyString())
                .user(anyString())
                .call()
                .entity(ReportDraft.class))
                .thenReturn(badDraft)
                .thenReturn(goodDraft);

        MergedData mergedData = new MergedData("summary", 100, Map.of());
        ReportResult result = reportAgent.generate(mergedData);

        assertThat(result).isNotNull();
        assertThat(result.draft()).isEqualTo(goodDraft);
        assertThat(result.reconciliation().ok()).isTrue();
        verify(chatClient.prompt().system(anyString()).user(anyString()).call(), times(2))
                .entity(ReportDraft.class);
    }
}
