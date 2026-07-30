package com.insightflow.service.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 平台 L2 表达/意图粗分类器；纯函数，无 DB 依赖，全 Workspace 共用同一份规则。
 *
 * <p>与 L1 的 {@link RuleFirstIssueClassifier} 同口径打分（排除优先于正向匹配，
 * priority &gt; hits &gt; longest 稳定降序），但语义边界不同：L1 允许 0～2 个主题、
 * 零命中交给编排层写 topic_general；L2 每条反馈必须恰好产出 1 个主标签
 * （spec §3.1："每条评论必有 L2，至少 expr_other"），零命中或多意图并列都必须在
 * 分类器内部收敛为单一结果，不能像 L1 一样把"不确定"上交给复核队列——因为 L2
 * 明确规定不进人工复核（spec §3.3）。</p>
 *
 * <p>同分处理：当排序第 1、2 名在 priority+hits 上完全相同，说明文本同时携带两种
 * 强度相近的意图（如"建议"与"吐槽"混合出现），此时仍以第 1 名（priority 更高者）
 * 作为 primary_expression，但标记 mixedExpression=true 并把 confidence 降到 0.5，
 * 让下游统计知道这不是一次干净利落的判定。</p>
 */
public class ExpressionClassifier {

    /** 规则列表；每次分类遍历，分类器本身无状态。 */
    private final List<ExpressionRule> rules;

    /** 构造分类器；规则来自 ExpressionRulesLoader，禁止运行期改写。 */
    public ExpressionClassifier(List<ExpressionRule> rules) {
        this.rules = rules;
    }

    /**
     * 对已归一文本分类；永远返回非空结果——零命中或空文本时回退到
     * {@link ExpressionDefaults#otherClassification()}，因为 L2 没有"未分类"状态。
     */
    public ExpressionClassification classify(String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return ExpressionDefaults.otherClassification();
        }

        // 排除先于正向判定，与 L1 同口径：命中排除词的规则直接出局。
        List<Candidate> candidates = new ArrayList<>();
        for (ExpressionRule rule : rules) {
            if (hitsAny(normalizedText, rule.excludePatterns())) {
                continue;
            }
            int hits = countHits(normalizedText, rule.anyPatterns());
            if (hits == 0) {
                continue;
            }
            int longest = longestHitLength(normalizedText, rule.anyPatterns());
            candidates.add(new Candidate(rule, hits, longest));
        }

        // 零命中直接回退 expr_other，confidence=1.0——这是"确定为其他"而非不确定。
        if (candidates.isEmpty()) {
            return ExpressionDefaults.otherClassification();
        }

        // 稳定排序：业务优先级 > 命中词数量 > 最长命中词长度，与 L1 完全同口径。
        candidates.sort(Comparator
                .comparingInt((Candidate c) -> c.rule.priority()).reversed()
                .thenComparingInt((Candidate c) -> c.hits).reversed()
                .thenComparingInt((Candidate c) -> c.longest).reversed());

        Candidate top = candidates.get(0);
        // 第 2 名与第 1 名同分：标记 mixed，但仍以第 1 名为 primary，因为 L2 不允许多主标签。
        boolean mixed = candidates.size() > 1 && isTied(top, candidates.get(1));
        double confidence = mixed ? 0.5 : 1.0;
        return new ExpressionClassification(top.rule.canonicalKey(), confidence, mixed);
    }

    /** 命中任一排除词即整条规则出局，逻辑与 L1 一致。 */
    private boolean hitsAny(String text, List<String> patterns) {
        for (String p : patterns) {
            if (p != null && !p.isEmpty() && text.contains(p)) {
                return true;
            }
        }
        return false;
    }

    /** 统计 any_patterns 命中数，每个 pattern 独立计数一次。 */
    private int countHits(String text, List<String> patterns) {
        int count = 0;
        for (String p : patterns) {
            if (p != null && !p.isEmpty() && text.contains(p)) {
                count++;
            }
        }
        return count;
    }

    /** 最长命中词长度，用于同分时的稳定排序次维度。 */
    private int longestHitLength(String text, List<String> patterns) {
        int longest = 0;
        for (String p : patterns) {
            if (p != null && !p.isEmpty() && text.contains(p) && p.length() > longest) {
                longest = p.length();
            }
        }
        return longest;
    }

    /** priority+hits 完全相同视为同分，触发 mixedExpression。 */
    private boolean isTied(Candidate first, Candidate second) {
        return first.rule.priority() == second.rule.priority() && first.hits == second.hits;
    }

    /** 内部候选，携带排序所需字段；与 L1 的 Candidate 同构但类型不同，不跨层复用。 */
    private record Candidate(ExpressionRule rule, int hits, int longest) {
    }
}
