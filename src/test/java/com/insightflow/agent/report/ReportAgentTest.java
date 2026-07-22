package com.insightflow.agent.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class ReportAgentTest {

    private final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    private final ReconciliationEngine reconciliationEngine = new ReconciliationEngine();
    private final ReportTools reportTools = mock(ReportTools.class);
    private final ReportAgent reportAgent = new ReportAgent(chatClient, reconciliationEngine, reportTools);

    @Test
    void generatesReportText() {
        when(chatClient.prompt()
                .system(anyString())
                .user(anyString())
                .call()
                .chatResponse()
                .getResult()
                .getOutput()
                .getContent())
                .thenReturn("本周工单分析报告：共收到100条工单...");

        MergedData mergedData = new MergedData("summary", 100, Map.of());
        String result = reportAgent.generate(mergedData);

        assertThat(result).isNotNull();
        assertThat(result).contains("100条工单");
    }
}
