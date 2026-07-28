package com.insightflow.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 运营分析 Prompt 目录测试：版本和模板正文必须由一个入口维护，避免 Agent 各自复制后悄然漂移。
 */
class OperationalPromptCatalogTest {

    /**
     * 四类非聊天 Agent 都应具备可追踪版本与非空系统提示词，后续运行记录才能按版本比较效果。
     */
    @Test
    void exposesVersionedPromptsForAllOperationalAgents() {
        OperationalPromptCatalog catalog = new OperationalPromptCatalog();

        assertThat(catalog.classification().version()).isEqualTo("classification:v1");
        assertThat(catalog.sentiment().version()).isEqualTo("sentiment:v1");
        assertThat(catalog.risk().version()).isEqualTo("risk:v1");
        assertThat(catalog.report().version()).isEqualTo("report:v1");
        assertThat(catalog.classification().systemPrompt()).isNotBlank();
        assertThat(catalog.sentiment().systemPrompt()).isNotBlank();
        assertThat(catalog.risk().systemPrompt()).isNotBlank();
        assertThat(catalog.report().systemPrompt()).isNotBlank();
    }
}
