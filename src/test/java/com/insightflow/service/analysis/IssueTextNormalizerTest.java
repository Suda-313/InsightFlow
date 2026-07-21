package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 归一化层只提升规则召回，不改变语义；归一结果只用于匹配，不写库。
 */
class IssueTextNormalizerTest {

    /** 种子归一表应把"登不上"归一到"登录失败"。 */
    @Test
    void normalizesSynonymVariant() {
        IssueRulesLoader loader = new IssueRulesLoader();
        loader.load();
        IssueTextNormalizer normalizer = new IssueTextNormalizer(loader.normalizeMappings());

        String result = normalizer.normalize("我的账号登不上游戏了");

        assertThat(result).contains("登录失败");
    }

    /** 全角字母应转半角，避免大小写或全角差异导致漏匹配。 */
    @Test
    void normalizesFullWidthToHalfWidth() {
        IssueTextNormalizer normalizer = new IssueTextNormalizer(java.util.List.of());

        String result = normalizer.normalize("ＢＵＧ闪退");

        assertThat(result).contains("bug");
    }

    /** 空输入不抛异常，返回空串。 */
    @Test
    void emptyInputReturnsEmpty() {
        IssueTextNormalizer normalizer = new IssueTextNormalizer(java.util.List.of());

        assertThat(normalizer.normalize("")).isEmpty();
        assertThat(normalizer.normalize(null)).isEmpty();
    }
}
