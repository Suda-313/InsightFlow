package com.insightflow.service.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 规则优先主题分类器；纯函数，无 DB 依赖。
 *
 * <p>分类流程遵循"先排除、再正向"的顺序：命中 exclude_patterns 的规则直接出局，
 * 避免充值/退款等噪声语境把登录失败等主题带进来。正向匹配时 any_patterns 任一中招
 * 即成为候选；若规则还声明了 all_patterns，则必须全部命中才保留，用于需要多个
 * 关键词共同约束的精细主题。</p>
 *
 * <p>排序维度按 priority &gt; hits &gt; longest 稳定降序：priority 体现业务重要性；
 * hits 反映文本与主题的密集程度；longest 作为同分时的稳定次维度，防止排序抖动。
 * 只取前 2 条是因为后续 Qwen 层最多只需处理 2 个候选，超过 2 个会让人工复核成本
 * 陡增，同时约束歧义范围。</p>
 *
 * <p>第 2 名与第 1 名在 priority+hits 上同分时，标记 assignmentMethod 为
 * "ambiguous"，confidence 取 0.5：这表示规则无法唯一判定，把决策权交给后续
 * Qwen 层或人工，而不是武断地二选一。无候选时返回空列表，由投影编排层写入
 * {@link TopicPackDefaults#TOPIC_GENERAL_KEY}，不进复核队列。</p>
 */
public class RuleFirstIssueClassifier implements IssueClassifier {

    /** 规则列表；每次分类遍历，分类器本身无状态。 */
    private final List<IssueRule> rules;

    /** 构造分类器；规则来自 IssueRulesLoader，禁止运行期改写。 */
    public RuleFirstIssueClassifier(List<IssueRule> rules) {
        this.rules = rules;
    }

    @Override
    public List<Classification> classify(String normalizedText) {
        if (normalizedText == null || normalizedText.isEmpty()) {
            return List.of();
        }

        // 排除先于正向判定，防止噪声词把相关主题错误地带入候选池。
        List<Candidate> candidates = new ArrayList<>();
        for (IssueRule rule : rules) {
            if (hitsAny(normalizedText, rule.excludePatterns())) {
                continue;
            }

            int hits = countHits(normalizedText, rule.anyPatterns());
            if (hits == 0) {
                continue;
            }

            // all_patterns 是可选的 AND 约束；为空时不影响候选资格。
            if (!rule.allPatterns().isEmpty() && !hitsAll(normalizedText, rule.allPatterns())) {
                continue;
            }

            int longest = longestHitLength(normalizedText, rule.anyPatterns());
            candidates.add(new Candidate(rule, hits, longest));
        }

        // 稳定排序：业务优先级 &gt; 命中词数量 &gt; 最长命中词长度。
        candidates.sort(Comparator
                .comparingInt((Candidate c) -> c.rule.priority()).reversed()
                .thenComparingInt((Candidate c) -> c.hits).reversed()
                .thenComparingInt((Candidate c) -> c.longest).reversed());

        // 最多产出 2 个主题；第 2 名与第 1 名同分时标记 ambiguous。
        List<Classification> result = new ArrayList<>();
        for (int i = 0; i < Math.min(2, candidates.size()); i++) {
            Candidate c = candidates.get(i);
            String method = "rule";
            if (i == 1 && isTied(candidates.get(0), c)) {
                method = "ambiguous";
            }
            double confidence = "ambiguous".equals(method) ? 0.5 : 1.0;
            result.add(new Classification(c.rule.canonicalKey(), confidence, method));
        }
        return result;
    }

    /**
     * 返回需要人工复核的受控原因；分类结果仍保持最多两条，防止长评直接放大指标。
     */
    public String reviewReason(String normalizedText, List<Classification> classifications) {
        // 零命中走 GENERAL 出口写 topic_general link，不再创建复核候选。
        if (classifications.isEmpty()) {
            return null;
        }
        if (classifications.stream().anyMatch(item -> "ambiguous".equals(item.assignmentMethod()))) {
            return "ambiguous_topics";
        }
        int candidateCount = 0;
        for (IssueRule rule : rules) {
            if (!hitsAny(normalizedText, rule.excludePatterns())
                    && countHits(normalizedText, rule.anyPatterns()) > 0
                    && (rule.allPatterns().isEmpty() || hitsAll(normalizedText, rule.allPatterns()))) {
                candidateCount++;
            }
        }
        return candidateCount > 2 ? "too_many_topics" : null;
    }

    /**
     * 命中任一排除词即整条规则出局。
     *
     * <p>排除词通常是否定或转移语境的业务词（如"充值"之于登录失败），
     * 先过滤掉这些规则可以显著降低误命中。</p>
     */
    private boolean hitsAny(String text, List<String> patterns) {
        for (String p : patterns) {
            if (p != null && !p.isEmpty() && text.contains(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 统计 any_patterns 命中数（去重计词，避免重复词膨胀 hits）。
     *
     * <p>每个 pattern 独立计数，而不是按出现次数，保证不同规则之间的
     * hits 口径一致，便于 priority+hits 排序。</p>
     */
    private int countHits(String text, List<String> patterns) {
        int count = 0;
        for (String p : patterns) {
            if (p != null && !p.isEmpty() && text.contains(p)) {
                count++;
            }
        }
        return count;
    }

    /**
     * all_patterns 需全部命中才算候选。
     *
     * <p>这是 any_patterns 的 AND 补充：any 解决召回，all 解决精确度，
     * 防止单个宽泛词把文本错误关联到需要多重线索的主题。</p>
     */
    private boolean hitsAll(String text, List<String> patterns) {
        for (String p : patterns) {
            if (p == null || p.isEmpty() || !text.contains(p)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 最长命中词长度，用于同分时的稳定排序。
     *
     * <p>当 priority 与 hits 都相同，选择命中词更长的规则排在前面，
     * 因为长词通常语义更精确，可以减少随机波动带来的排序抖动。</p>
     */
    private int longestHitLength(String text, List<String> patterns) {
        int longest = 0;
        for (String p : patterns) {
            if (p != null && !p.isEmpty() && text.contains(p) && p.length() > longest) {
                longest = p.length();
            }
        }
        return longest;
    }

    /**
     * priority+hits 完全相同视为同分，第 2 名标记 ambiguous。
     *
     * <p>这里只比较 priority 和 hits，因为 longest 已经是稳定次维度；
     * 若连 priority+hits 都相同，说明规则无法区分，应把决策权上交。</p>
     */
    private boolean isTied(Candidate first, Candidate second) {
        return first.rule.priority() == second.rule.priority() && first.hits == second.hits;
    }

    /**
     * 内部候选，携带排序所需字段。
     *
     * <p>把 rule、hits、longest 打包成一个不可变记录，
     * 避免 classify 主循环里频繁访问规则字段并提高可读性。</p>
     */
    private record Candidate(IssueRule rule, int hits, int longest) {
    }
}
