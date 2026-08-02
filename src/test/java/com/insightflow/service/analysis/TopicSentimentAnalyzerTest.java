package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 保护主题级情绪，不允许再把混合长评压缩为一条整体正面或负面标签。
 */
class TopicSentimentAnalyzerTest {

    /**
     * 若移除“按主题词窗口判断”的逻辑，两个主题都会得到相同情绪，测试应立即失败。
     */
    @Test
    void assignsOppositeSentimentsToDifferentTopicsInOneMixedReview() {
        TopicSentimentAnalyzer analyzer = new TopicSentimentAnalyzer();

        List<TopicSentiment> results = analyzer.analyze(
                "画面很好但是网络卡顿严重，经常掉线",
                List.of("graphics", "network"));

        assertThat(results).containsExactly(
                new TopicSentiment("graphics", "positive"),
                new TopicSentiment("network", "negative"));
    }
}
