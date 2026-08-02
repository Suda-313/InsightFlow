package com.insightflow.knowledge;

/**
 * 受控检索参数：词法/向量各取 TopK，RRF 合并后输出 candidateLimit 条候选。
 *
 * <p>生产路径最终仍只向模型呈现 8 条；评测路径可取 Top50 计算 Candidate Recall。</p>
 */
public record KnowledgeSearchOptions(
        int lexicalTopK,
        int vectorTopK,
        int candidateLimit,
        String lexicalQuery,
        boolean enrichedLexicalText) {

    /** 历史 v1：content_tsv + Top32，最终 limit 由调用方截断。 */
    public static KnowledgeSearchOptions legacyV1(String query, int limit) {
        return new KnowledgeSearchOptions(32, 32, limit, query, false);
    }

    /** R1 生产/评测：Top40/40、RRF Top50，词法为加权 trigram（title/section/version/body）。 */
    public static KnowledgeSearchOptions rrfV2(String expandedLexicalQuery) {
        return new KnowledgeSearchOptions(40, 40, 50, expandedLexicalQuery, true);
    }
}
