package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 规则优先分类器是纯函数；未命中不伪造主题，同分进 ambiguous，最多 2 主题。
 */
class RuleFirstIssueClassifierTest {

    /** 超过两条有效规则候选时，统计仍只保留前两项，剩余不确定性必须进入人工复核。 */
    @Test
    void marksMoreThanTwoMatchingRulesForReview() {
        RuleFirstIssueClassifier classifier = new RuleFirstIssueClassifier(List.of(
                new IssueRule("a", "A", 3, List.of("甲"), List.of(), List.of()),
                new IssueRule("b", "B", 2, List.of("乙"), List.of(), List.of()),
                new IssueRule("c", "C", 1, List.of("丙"), List.of(), List.of())));

        List<Classification> classifications = classifier.classify("甲乙丙");

        assertThat(classifications).hasSize(2);
        assertThat(classifier.reviewReason("甲乙丙", classifications)).isEqualTo("too_many_topics");
    }

    /** 命中 login_failure 规则应返回单条 rule 关联，confidence=1.0。 */
    @Test
    void classifiesSingleHit() {
        IssueRulesLoader loader = new IssueRulesLoader();
        loader.load();
        RuleFirstIssueClassifier classifier = new RuleFirstIssueClassifier(loader.rules());

        List<Classification> result = classifier.classify("我的账号登录失败了");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).canonicalKey()).isEqualTo("login_failure");
        assertThat(result.get(0).assignmentMethod()).isEqualTo("rule");
        assertThat(result.get(0).confidence()).isEqualTo(1.0);
    }

    /** 文本同时命中两个不同优先级主题应返回 2 条关联。 */
    @Test
    void classifiesTwoDistinctTopics() {
        IssueRulesLoader loader = new IssueRulesLoader();
        loader.load();
        RuleFirstIssueClassifier classifier = new RuleFirstIssueClassifier(loader.rules());

        List<Classification> result = classifier.classify("登录失败 而且没到账");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(Classification::canonicalKey)
                .contains("login_failure", "payment_recharge");
    }

    /** 无任何规则命中应返回空列表；reviewReason 为 null，投影层写 topic_general。 */
    @Test
    void returnsEmptyWhenNoMatch() {
        IssueRulesLoader loader = new IssueRulesLoader();
        loader.load();
        RuleFirstIssueClassifier classifier = new RuleFirstIssueClassifier(loader.rules());

        List<Classification> result = classifier.classify("今天天气不错想出门走走");

        assertThat(result).isEmpty();
        assertThat(classifier.reviewReason("今天天气不错想出门走走", result)).isNull();
    }

    /** 命中 exclude_patterns 的规则应被排除。 */
    @Test
    void excludesByExcludePattern() {
        IssueRulesLoader loader = new IssueRulesLoader();
        loader.load();
        RuleFirstIssueClassifier classifier = new RuleFirstIssueClassifier(loader.rules());

        List<Classification> result = classifier.classify("充值时登录失败");

        assertThat(result).extracting(Classification::canonicalKey).doesNotContain("login_failure");
    }

    /** 同 priority 且同 hits 时第二名标记为 ambiguous，confidence=0.5。 */
    @Test
    void classifiesAmbiguousOnPriorityHitsTie() {
        IssueRule ruleA = new IssueRule(
                "login_failure", "登录失败", 50,
                List.of("登录"), List.of(), List.of());
        IssueRule ruleB = new IssueRule(
                "payment_recharge", "充值到账", 50,
                List.of("到账"), List.of(), List.of());
        RuleFirstIssueClassifier classifier = new RuleFirstIssueClassifier(List.of(ruleA, ruleB));

        List<Classification> result = classifier.classify("登录 到账");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).canonicalKey()).isEqualTo("login_failure");
        assertThat(result.get(0).assignmentMethod()).isEqualTo("rule");
        assertThat(result.get(0).confidence()).isEqualTo(1.0);
        assertThat(result.get(1).canonicalKey()).isEqualTo("payment_recharge");
        assertThat(result.get(1).assignmentMethod()).isEqualTo("ambiguous");
        assertThat(result.get(1).confidence()).isEqualTo(0.5);
    }
}
