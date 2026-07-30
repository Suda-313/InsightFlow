package com.insightflow.knowledge;

import com.github.f4b6a3.uuid.UuidCreator;
import com.insightflow.entity.KnowledgeDocumentType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL pgvector 的受控读写适配器。
 *
 * <p>向量通过 {@code CAST(... AS vector)} 写入和查询，不能退化为可检索的普通文本；
 * 查询 SQL 固定组织、Workspace、发布状态及 RRF 规则，调用方无法拼接任意 SQL。</p>
 */
@Component
public class JdbcKnowledgeVectorStore implements KnowledgeVectorStore {

    /** 写入向量时使用 JDBC 批处理，避免 JPA 将 pgvector 错误映射为普通文本。 */
    private final JdbcTemplate jdbcTemplate;

    /** 命名参数用于安全展开固定枚举类型列表，不拼接任何用户输入。 */
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    /** 注入同一数据源的两个 JDBC 门面，保证发布事务可以同时回滚版本状态与切片写入。 */
    public JdbcKnowledgeVectorStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    /**
     * 批量写入某一已发布版本的全部切片和向量。
     * 任一切片失败均应由外层发布事务回滚，避免出现“已发布但没有完整可检索证据”的版本。
     */
    @Override
    public void store(Long versionId, List<EmbeddedChunk> chunks) {
        String sql = "INSERT INTO knowledge_chunk (public_id, version_id, chunk_no, content, section_heading, "
                + "lexical_text, embedding, token_count) "
                + "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS vector), ?)";
        jdbcTemplate.batchUpdate(sql, chunks, chunks.size(), (statement, chunk) -> {
            statement.setObject(1, UuidCreator.getTimeOrdered());
            statement.setLong(2, versionId);
            statement.setInt(3, chunk.chunkNo());
            statement.setString(4, chunk.content());
            statement.setString(5, chunk.sectionHeading());
            statement.setString(6, chunk.lexicalText());
            statement.setString(7, vectorLiteral(chunk.embedding()));
            statement.setInt(8, chunk.tokenCount());
        });
    }

    @Override
    public KnowledgeSearchResult searchWithOptions(Long organizationId, Long workspaceId, String query,
            List<KnowledgeDocumentType> types, List<Double> queryEmbedding, KnowledgeSearchOptions options) {
        if (options.enrichedLexicalText()) {
            return searchEnriched(organizationId, workspaceId, query, types, queryEmbedding, options);
        }
        return searchLegacyContentTsv(organizationId, workspaceId, types, queryEmbedding, options);
    }

    /** v1：仅 content_tsv，lexical/semantic 各 Top32。 */
    private KnowledgeSearchResult searchLegacyContentTsv(
            Long organizationId,
            Long workspaceId,
            List<KnowledgeDocumentType> types,
            List<Double> queryEmbedding,
            KnowledgeSearchOptions options) {
        String typeFilter = types.isEmpty() ? "" : " AND d.document_type IN (:types)";
        String effectiveFilter = effectiveWindowFilter();
        String visibleFilter = visibleFilter(typeFilter, effectiveFilter);
        String effectiveWindow = "CASE WHEN v.effective_from IS NULL AND v.effective_to IS NULL THEN 'always' "
                + "ELSE coalesce(to_char(v.effective_from AT TIME ZONE 'UTC', 'YYYY-MM-DD'), 'open') "
                + "|| '..' || coalesce(to_char(v.effective_to AT TIME ZONE 'UTC', 'YYYY-MM-DD'), 'open') END";
        String sql = "WITH visible AS ("
                + "SELECT c.public_id chunk_id, c.content, c.content_tsv, c.section_heading, d.document_type, "
                + effectiveWindow + " effective_window, "
                + "d.public_id document_id, "
                + "v.public_id version_id, v.version_no, d.title "
                + "FROM knowledge_chunk c JOIN knowledge_document_version v ON v.id = c.version_id "
                + "JOIN knowledge_document d ON d.id = v.document_id " + visibleFilter + "), "
                + "lexical AS (SELECT chunk_id, row_number() OVER (ORDER BY ts_rank_cd(content_tsv, "
                + "websearch_to_tsquery('simple', :query)) DESC) rank_no FROM visible "
                + "WHERE content_tsv @@ websearch_to_tsquery('simple', :query) LIMIT :lexicalTopK), "
                + "semantic AS (SELECT c.public_id chunk_id, row_number() OVER (ORDER BY c.embedding <=> "
                + "CAST(:vector AS vector)) rank_no FROM knowledge_chunk c "
                + "JOIN knowledge_document_version v ON v.id = c.version_id "
                + "JOIN knowledge_document d ON d.id = v.document_id " + visibleFilter + " LIMIT :vectorTopK) "
                + "SELECT visible.*, COALESCE(1.0 / (60 + lexical.rank_no), 0) "
                + "+ COALESCE(1.0 / (60 + semantic.rank_no), 0) score, "
                + "CASE WHEN lexical.rank_no IS NOT NULL AND semantic.rank_no IS NULL THEN 'lexical' "
                + "WHEN semantic.rank_no IS NOT NULL AND lexical.rank_no IS NULL THEN 'vector' "
                + "ELSE 'both' END source_kind "
                + "FROM visible "
                + "LEFT JOIN lexical USING(chunk_id) LEFT JOIN semantic USING(chunk_id) "
                + "WHERE lexical.chunk_id IS NOT NULL OR semantic.chunk_id IS NOT NULL "
                + "ORDER BY score DESC LIMIT :candidateLimit";
        return queryMerged(
                sql,
                organizationId,
                workspaceId,
                options.lexicalQuery(),
                options.lexicalQuery(),
                queryEmbedding,
                types,
                options);
    }

    /** v3：加权 trigram 词法（title/section/version/body）+ 向量，各 Top40，RRF Top50。 */
    private KnowledgeSearchResult searchEnriched(
            Long organizationId,
            Long workspaceId,
            String questionQuery,
            List<KnowledgeDocumentType> types,
            List<Double> queryEmbedding,
            KnowledgeSearchOptions options) {
        String typeFilter = types.isEmpty() ? "" : " AND d.document_type IN (:types)";
        String effectiveFilter = effectiveWindowFilter();
        String visibleFilter = visibleFilter(typeFilter, effectiveFilter);
        String effectiveWindow = "CASE WHEN v.effective_from IS NULL AND v.effective_to IS NULL THEN 'always' "
                + "ELSE coalesce(to_char(v.effective_from AT TIME ZONE 'UTC', 'YYYY-MM-DD'), 'open') "
                + "|| '..' || coalesce(to_char(v.effective_to AT TIME ZONE 'UTC', 'YYYY-MM-DD'), 'open') END";
        String weightedScore = KnowledgeLexicalFieldWeights.weightedScoreExpression();
        String matchPredicate = KnowledgeLexicalFieldWeights.matchPredicate();
        String sql = "WITH visible AS ("
                + "SELECT c.public_id chunk_id, c.content, c.lexical_text, c.section_heading, d.document_type, "
                + effectiveWindow + " effective_window, "
                + "d.public_id document_id, v.public_id version_id, v.version_no, d.title "
                + "FROM knowledge_chunk c JOIN knowledge_document_version v ON v.id = c.version_id "
                + "JOIN knowledge_document d ON d.id = v.document_id " + visibleFilter + "), "
                + "lexical AS (SELECT visible.chunk_id, row_number() OVER (ORDER BY "
                + weightedScore + " DESC) rank_no FROM visible "
                + "WHERE " + matchPredicate + " LIMIT :lexicalTopK), "
                + "semantic AS (SELECT c.public_id chunk_id, row_number() OVER (ORDER BY c.embedding <=> "
                + "CAST(:vector AS vector)) rank_no FROM knowledge_chunk c "
                + "JOIN knowledge_document_version v ON v.id = c.version_id "
                + "JOIN knowledge_document d ON d.id = v.document_id " + visibleFilter + " LIMIT :vectorTopK) "
                + "SELECT visible.chunk_id, visible.content, visible.document_id, visible.version_id, "
                + "visible.version_no, visible.title, visible.document_type, visible.section_heading, "
                + "visible.effective_window, "
                + "COALESCE(1.0 / (60 + lexical.rank_no), 0) + COALESCE(1.0 / (60 + semantic.rank_no), 0) score, "
                + "CASE WHEN lexical.rank_no IS NOT NULL AND semantic.rank_no IS NULL THEN 'lexical' "
                + "WHEN semantic.rank_no IS NOT NULL AND lexical.rank_no IS NULL THEN 'vector' "
                + "ELSE 'both' END source_kind "
                + "FROM visible "
                + "LEFT JOIN lexical USING(chunk_id) LEFT JOIN semantic USING(chunk_id) "
                + "WHERE lexical.chunk_id IS NOT NULL OR semantic.chunk_id IS NOT NULL "
                + "ORDER BY score DESC LIMIT :candidateLimit";
        return queryMerged(
                sql,
                organizationId,
                workspaceId,
                questionQuery,
                options.lexicalQuery(),
                queryEmbedding,
                types,
                options);
    }

    private KnowledgeSearchResult queryMerged(
            String sql,
            Long organizationId,
            Long workspaceId,
            String questionQuery,
            String expandedQuery,
            List<Double> queryEmbedding,
            List<KnowledgeDocumentType> types,
            KnowledgeSearchOptions options) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("workspaceId", workspaceId)
                .addValue("query", expandedQuery == null ? "" : expandedQuery.trim())
                .addValue("questionQuery", questionQuery == null ? "" : questionQuery.trim())
                .addValue("expandedQuery", expandedQuery == null ? "" : expandedQuery.trim())
                .addValue("titleWeight", KnowledgeLexicalFieldWeights.TITLE)
                .addValue("sectionWeight", KnowledgeLexicalFieldWeights.SECTION)
                .addValue("versionWeight", KnowledgeLexicalFieldWeights.VERSION)
                .addValue("bodyWeight", KnowledgeLexicalFieldWeights.BODY)
                .addValue("similarityThreshold", KnowledgeLexicalFieldWeights.SIMILARITY_THRESHOLD)
                .addValue("bodySimilarityThreshold", KnowledgeLexicalFieldWeights.BODY_SIMILARITY_THRESHOLD)
                .addValue("vector", vectorLiteral(queryEmbedding))
                .addValue("lexicalTopK", options.lexicalTopK())
                .addValue("vectorTopK", options.vectorTopK())
                .addValue("candidateLimit", options.candidateLimit());
        if (!types.isEmpty()) {
            parameters.addValue("types", types.stream().map(Enum::name).toList());
        }
        Set<UUID> lexicalOnly = new HashSet<>();
        Set<UUID> vectorOnly = new HashSet<>();
        Set<UUID> both = new HashSet<>();
        List<SearchCandidate> candidates = namedJdbcTemplate.query(sql, parameters, (resultSet, rowNumber) -> {
            UUID chunkId = resultSet.getObject("chunk_id", UUID.class);
            String sourceKind = resultSet.getString("source_kind");
            if ("lexical".equals(sourceKind)) {
                lexicalOnly.add(chunkId);
            } else if ("vector".equals(sourceKind)) {
                vectorOnly.add(chunkId);
            } else {
                both.add(chunkId);
            }
            return new SearchCandidate(
                    resultSet.getObject("document_id", UUID.class),
                    resultSet.getObject("version_id", UUID.class),
                    resultSet.getInt("version_no"),
                    chunkId,
                    resultSet.getString("title"),
                    resultSet.getString("content"),
                    resultSet.getDouble("score"),
                    resultSet.getString("document_type"),
                    resultSet.getString("section_heading"),
                    resultSet.getString("effective_window"));
        });
        return new KnowledgeSearchResult(candidates, lexicalOnly, vectorOnly, both);
    }

    private String visibleFilter(String typeFilter, String effectiveFilter) {
        return "WHERE d.organization_id = :organizationId "
                + "AND (d.target_workspace_id IS NULL OR d.target_workspace_id = :workspaceId) "
                + "AND v.status = 'PUBLISHED'" + effectiveFilter + typeFilter;
    }

    private String effectiveWindowFilter() {
        return " AND (v.effective_from IS NULL OR v.effective_from <= CURRENT_TIMESTAMP) "
                + "AND (v.effective_to IS NULL OR v.effective_to >= CURRENT_TIMESTAMP)";
    }

    /** pgvector 使用方括号字面量；数值只来自嵌入模型，绝不把用户原文拼入 SQL。 */
    private String vectorLiteral(List<Double> embedding) {
        return embedding.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }
}
