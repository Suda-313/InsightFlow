package com.insightflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.openai.OpenAiEmbeddingModel;

/** DashScope embedding 网关必须遵守供应商单次最多 10 条的批次限制。 */
@ExtendWith(MockitoExtension.class)
class DashScopeKnowledgeEmbeddingGatewayTest {

    @Mock private OpenAiEmbeddingModel embeddingModel;

    /** 超过 10 个 chunk 时应拆成多批调用，且合并后顺序与输入一致。 */
    @Test
    void splitsEmbedRequestsIntoBatchesOfTen() {
        List<String> inputs = IntStream.range(0, 23).mapToObj(i -> "chunk-" + i).toList();
        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            List<String> batch = invocation.getArgument(0);
            return batch.stream().map(text -> List.of((double) text.hashCode())).toList();
        });

        List<List<Double>> result = new DashScopeKnowledgeEmbeddingGateway(embeddingModel).embed(inputs);

        assertThat(result).hasSize(23);
        assertThat(result.get(0).get(0)).isEqualTo((double) "chunk-0".hashCode());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingModel, times(3)).embed(captor.capture());
        assertThat(captor.getAllValues().get(0)).hasSize(10);
        assertThat(captor.getAllValues().get(1)).hasSize(10);
        assertThat(captor.getAllValues().get(2)).hasSize(3);
    }

    /** 空输入不触发外部模型调用。 */
    @Test
    void returnsEmptyForEmptyInput() {
        List<List<Double>> result = new DashScopeKnowledgeEmbeddingGateway(embeddingModel).embed(List.of());
        assertThat(result).isEmpty();
        verify(embeddingModel, times(0)).embed(anyList());
    }
}
