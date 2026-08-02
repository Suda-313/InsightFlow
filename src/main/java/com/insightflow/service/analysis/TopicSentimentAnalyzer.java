package com.insightflow.service.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * 按主题相关文本窗口判断情绪的轻量规则器。
 *
 * <p>它不调用模型、不生成新主题。长评中不同主题可得到不同情绪；没有可靠线索时
 * 返回 neutral，而不是伪装为正面或负面。</p>
 */
public class TopicSentimentAnalyzer {

    /** 与每个稳定主题相关的词，用于将整段评论缩小到可解释的局部窗口。 */
    private static final List<String> NETWORK_WORDS = List.of("网络", "掉线", "延迟", "卡顿", "断线");
    private static final List<String> GAMEPLAY_WORDS = List.of("玩法", "副本", "关卡", "战斗", "bug", "闪退");
    private static final List<String> GRAPHICS_WORDS = List.of("画面", "美术", "建模", "特效");
    private static final List<String> POSITIVE_WORDS = List.of("好", "优秀", "喜欢", "流畅", "惊艳", "不错");
    private static final List<String> NEGATIVE_WORDS = List.of("差", "严重", "卡", "掉线", "闪退", "失望", "垃圾", "崩溃");

    /** 对已分类主题逐项分析；输入主题顺序保持到输出中，方便投影事实稳定写入。 */
    public List<TopicSentiment> analyze(String text, List<String> canonicalKeys) {
        if (text == null || text.isBlank() || canonicalKeys == null || canonicalKeys.isEmpty()) {
            return List.of();
        }
        List<TopicSentiment> results = new ArrayList<>();
        for (String key : canonicalKeys) {
            results.add(new TopicSentiment(key, sentimentOf(topicWindow(text, key))));
        }
        return results;
    }

    /** 未找到主题词时回退整段文本；此时 neutral 比武断归因更安全。 */
    private String topicWindow(String text, String canonicalKey) {
        List<String> words = switch (canonicalKey) {
            case "bug_network", "network" -> NETWORK_WORDS;
            case "bug_gameplay", "gameplay" -> GAMEPLAY_WORDS;
            case "graphics" -> GRAPHICS_WORDS;
            default -> List.of();
        };
        int index = firstIndex(text, words);
        if (index < 0) {
            return text;
        }
        int start = Math.max(0, index - 12);
        int end = Math.min(text.length(), index + 16);
        // “但是/但”分隔的转折通常承载相反主题情绪，不能让前一主题吞入后一主题的负面词。
        int previousContrast = Math.max(text.lastIndexOf("但是", index), text.lastIndexOf("但", index));
        if (previousContrast >= 0) {
            start = Math.max(start, previousContrast + (text.startsWith("但是", previousContrast) ? 2 : 1));
        }
        int nextContrast = firstPositive(text.indexOf("但是", index), text.indexOf("但", index));
        if (nextContrast >= 0) {
            end = Math.min(end, nextContrast);
        }
        return text.substring(start, end);
    }

    /** 正负词同时出现时标记 mixed，使后续进入复核而不是压缩为单一整体情绪。 */
    private String sentimentOf(String text) {
        boolean positive = containsAny(text, POSITIVE_WORDS);
        boolean negative = containsAny(text, NEGATIVE_WORDS);
        if (positive && negative) {
            return "mixed";
        }
        if (positive) {
            return "positive";
        }
        if (negative) {
            return "negative";
        }
        return "neutral";
    }

    /** 返回最早主题词位置，避免依赖无序匹配导致结果抖动。 */
    private int firstIndex(String text, List<String> words) {
        return words.stream().mapToInt(text::indexOf).filter(index -> index >= 0).min().orElse(-1);
    }

    /** 在两个可能的分隔位置中选择最早的有效位置。 */
    private int firstPositive(int left, int right) {
        if (left < 0) {
            return right;
        }
        if (right < 0) {
            return left;
        }
        return Math.min(left, right);
    }

    /** 任一受控关键词命中即成立，关键词来源固定在本类，绝不接收用户自定义表达式。 */
    private boolean containsAny(String text, List<String> words) {
        return words.stream().anyMatch(text::contains);
    }
}
