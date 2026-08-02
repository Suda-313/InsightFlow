package com.insightflow.knowledge;

/**
 * 词法 trigram 检索的字段权重与相似度阈值。
 *
 * <p>标题与版本号优先于章节与正文，避免正文长文本稀释 exact 关键词命中；
 * 阈值针对中文短 query 略低于 pg_trgm 默认 0.3。</p>
 */
public final class KnowledgeLexicalFieldWeights {

    public static final double TITLE = 3.0d;
    public static final double SECTION = 2.0d;
    public static final double VERSION = 2.5d;
    public static final double BODY = 1.0d;
    /** 标题/章节较短，阈值略低。 */
    public static final double SIMILARITY_THRESHOLD = 0.12d;
    /** 正文 trigram 更易误命中，阈值略高。 */
    public static final double BODY_SIMILARITY_THRESHOLD = 0.15d;

    private KnowledgeLexicalFieldWeights() {
    }

    /**
     * 加权 trigram 分数 SQL 片段；visible 别名需含 title/section_heading/lexical_text/content/version_no。
     */
    static String weightedScoreExpression() {
        return "("
                + ":titleWeight * similarity(coalesce(visible.title, ''), :questionQuery)"
                + " + :sectionWeight * similarity(coalesce(visible.section_heading, ''), :questionQuery)"
                + " + :versionWeight * greatest("
                + "similarity('v' || visible.version_no::text, :questionQuery), "
                + "similarity('v' || visible.version_no::text, :expandedQuery), "
                + "similarity(visible.version_no::text, :questionQuery), "
                + "similarity(visible.version_no::text, :expandedQuery))"
                + " + :bodyWeight * similarity(coalesce(visible.lexical_text, visible.content), :questionQuery)"
                + ")";
    }

    /** 至少一个字段达到阈值才进入 lexical TopK；正文只用原问题，版本号可用扩展 token。 */
    static String matchPredicate() {
        return "("
                + "similarity(coalesce(visible.lexical_text, visible.content), :questionQuery) >= :bodySimilarityThreshold "
                + "OR similarity(coalesce(visible.title, ''), :questionQuery) >= :similarityThreshold "
                + "OR similarity(coalesce(visible.section_heading, ''), :questionQuery) >= :similarityThreshold "
                + "OR similarity('v' || visible.version_no::text, :questionQuery) >= :similarityThreshold "
                + "OR similarity('v' || visible.version_no::text, :expandedQuery) >= :similarityThreshold "
                + "OR similarity(visible.version_no::text, :questionQuery) >= :similarityThreshold "
                + "OR similarity(visible.version_no::text, :expandedQuery) >= :similarityThreshold"
                + ")";
    }
}
