package com.insightflow.agent.investigation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 将 {@link InvestigationResult} 确定性渲染为 Prompt 前的「调查摘要」骨架。
 *
 * <p>摘要只解析 Tool 已写入证据正文的数值与标题，不调用 LLM，也不推断缺失方向或幅度。
 * 同一输入多次调用必须产出相同 Markdown，保证评测与审计 Prompt 可比。</p>
 */
@Component
public class InvestigationSummarizer {

    /** 供 {@link InvestigationResult#renderForPrompt()} 在无 Spring 上下文时复用的无状态实例。 */
    static final InvestigationSummarizer DEFAULT = new InvestigationSummarizer();

    /** 趋势/比较证据正文：；{主题} 最近7天 {n} 条，前7天 {m} 条。 */
    private static final Pattern PERIOD_COUNTS =
            Pattern.compile("[；:：]\\s*(.+?)\\s+最近\\s*7\\s*天\\s*(\\d+)\\s*条，前\\s*7\\s*天\\s*(\\d+)\\s*条");

    /** 时间范围比较证据末尾的绝对变化字段。 */
    private static final Pattern ABSOLUTE_DELTA =
            Pattern.compile("绝对变化\\s*([+-]?\\d+)\\s*条");

    /** 趋势证据 id：trend:{canonicalKey}:{timeWindow} */
    private static final Pattern TREND_EVIDENCE_ID =
            Pattern.compile("^trend:[^:]+:(.+)$");

    /** 比较证据 id：comparison:{scope}:last_14_days */
    private static final Pattern COMPARISON_EVIDENCE_ID =
            Pattern.compile("^comparison:[^:]+:(.+)$");

    /** 分布证据 Top 列表中的主题名。 */
    private static final Pattern DISTRIBUTION_TOPIC =
            Pattern.compile("([^；]+?)\\s+\\d+\\s*条");

    /**
     * 生成固定结构的 Markdown 摘要段（含 {@code ## 调查摘要} 标题）。
     *
     * @param result 已完成 Tool 调用的调查结果，不可为 null
     */
    public String summarize(InvestigationResult result) {
        List<InvestigationEvidence> evidence = result.evidence();
        StringBuilder section = new StringBuilder("\n## 调查摘要\n");
        section.append("- 覆盖范围：")
                .append(formatCoverage(evidence))
                .append(" / ")
                .append(formatTimeWindow(evidence))
                .append("\n");

        List<String> keyChanges = formatKeyChanges(evidence);
        if (!keyChanges.isEmpty()) {
            section.append("- 关键变化：").append(String.join("；", keyChanges)).append("\n");
        }

        section.append("- 数据不足项：").append(formatInsufficientTitles(evidence)).append("\n");
        section.append("- 证据条数：").append(evidence.size()).append("\n");
        return section.toString();
    }

    /** 汇总趋势、比较与分布证据中的主题名；都没有时明确写未识别。 */
    private String formatCoverage(List<InvestigationEvidence> evidence) {
        LinkedHashSet<String> topics = new LinkedHashSet<>();
        for (InvestigationEvidence item : evidence) {
            if (item.tool() == InvestigationToolType.ISSUE_TREND && !"trend:unresolved".equals(item.id())) {
                parseTopicName(item.content()).ifPresent(topics::add);
            } else if (item.tool() == InvestigationToolType.PERIOD_COMPARISON && item.sufficient()) {
                parseTopicName(item.content())
                        .filter(name -> !"全工作区主题聚合".equals(name))
                        .ifPresent(topics::add);
            } else if (item.tool() == InvestigationToolType.TOPIC_DISTRIBUTION && item.sufficient()) {
                topics.addAll(parseDistributionTopics(item.content()));
            }
        }
        if (topics.isEmpty()) {
            return "未识别主题";
        }
        return String.join("、", topics);
    }

    /** 优先从证据 id 还原时间窗，否则回退到正文中的「最近7天」。 */
    private String formatTimeWindow(List<InvestigationEvidence> evidence) {
        for (InvestigationEvidence item : evidence) {
            String encoded = encodeTimeWindowFromId(item.id());
            if (encoded != null) {
                return humanizeTimeWindow(encoded);
            }
        }
        for (InvestigationEvidence item : evidence) {
            if (item.content().contains("最近7天") || item.content().contains("最近 7 天")) {
                return "近7天";
            }
            if (item.content().contains("最近14天") || item.content().contains("最近 14 天")) {
                return "近14天";
            }
        }
        return "未指定";
    }

    /**
     * 仅从 {@code ISSUE_TREND} / {@code PERIOD_COMPARISON} 且 sufficient 的证据解析关键变化；
     * 任一数值字段缺失则跳过该条，禁止推断。
     */
    private List<String> formatKeyChanges(List<InvestigationEvidence> evidence) {
        List<String> changes = new ArrayList<>();
        for (InvestigationEvidence item : evidence) {
            if (!item.sufficient()) {
                continue;
            }
            if (item.tool() == InvestigationToolType.ISSUE_TREND) {
                parseKeyChange(item).ifPresent(changes::add);
            } else if (item.tool() == InvestigationToolType.PERIOD_COMPARISON) {
                parseKeyChange(item).ifPresent(changes::add);
            }
        }
        return changes;
    }

    /** 解析单条趋势或比较证据的方向、幅度，并附上来源证据 id。 */
    private java.util.Optional<String> parseKeyChange(InvestigationEvidence item) {
        Matcher counts = PERIOD_COUNTS.matcher(item.content());
        if (!counts.find()) {
            return java.util.Optional.empty();
        }
        String metricName = counts.group(1).trim();
        int current = Integer.parseInt(counts.group(2));
        int previous = Integer.parseInt(counts.group(3));

        int delta;
        Matcher deltaMatcher = ABSOLUTE_DELTA.matcher(item.content());
        if (deltaMatcher.find()) {
            delta = Integer.parseInt(deltaMatcher.group(1));
        } else if (item.tool() == InvestigationToolType.ISSUE_TREND) {
            delta = current - previous;
        } else {
            return java.util.Optional.empty();
        }

        String direction = directionLabel(delta);
        String magnitude = formatMagnitude(delta);
        return java.util.Optional.of(
                metricName + " " + direction + magnitude + "（[" + item.id() + "]）");
    }

    /** 列出 sufficient=false 的证据标题；无则写「无」。 */
    private String formatInsufficientTitles(List<InvestigationEvidence> evidence) {
        List<String> titles = evidence.stream()
                .filter(item -> !item.sufficient())
                .map(InvestigationEvidence::title)
                .toList();
        if (titles.isEmpty()) {
            return "无";
        }
        return String.join("、", titles);
    }

    private java.util.Optional<String> parseTopicName(String content) {
        Matcher matcher = PERIOD_COUNTS.matcher(content);
        if (matcher.find()) {
            return java.util.Optional.of(matcher.group(1).trim());
        }
        return java.util.Optional.empty();
    }

    /** 从分布证据 Top 列表抽取至多前五项主题名，保持正文顺序。 */
    private List<String> parseDistributionTopics(String content) {
        int topMarker = content.indexOf("Top");
        if (topMarker < 0) {
            return List.of();
        }
        String tail = content.substring(topMarker);
        int colon = tail.indexOf('：');
        if (colon < 0) {
            colon = tail.indexOf(':');
        }
        if (colon < 0) {
            return List.of();
        }
        String listPart = tail.substring(colon + 1).replace('。', ' ').trim();
        List<String> topics = new ArrayList<>();
        for (String segment : listPart.split("；")) {
            Matcher matcher = DISTRIBUTION_TOPIC.matcher(segment.trim());
            if (matcher.find()) {
                topics.add(matcher.group(1).trim());
            }
            if (topics.size() >= 5) {
                break;
            }
        }
        return topics;
    }

    private String encodeTimeWindowFromId(String evidenceId) {
        Matcher trend = TREND_EVIDENCE_ID.matcher(evidenceId);
        if (trend.matches()) {
            return trend.group(1);
        }
        Matcher comparison = COMPARISON_EVIDENCE_ID.matcher(evidenceId);
        if (comparison.matches()) {
            return comparison.group(1);
        }
        return null;
    }

    private String humanizeTimeWindow(String encoded) {
        return switch (encoded) {
            case "last_14_days" -> "近14天";
            case "last_7_days" -> "近7天";
            default -> encoded.replace('_', ' ');
        };
    }

    private String directionLabel(int delta) {
        if (delta > 0) {
            return "上升";
        }
        if (delta < 0) {
            return "下降";
        }
        return "持平";
    }

    /** 幅度保留符号，与 Tool 正文「绝对变化 +N 条」口径一致。 */
    private String formatMagnitude(int delta) {
        if (delta > 0) {
            return "+" + delta + "条";
        }
        if (delta < 0) {
            return delta + "条";
        }
        return "0条";
    }
}
