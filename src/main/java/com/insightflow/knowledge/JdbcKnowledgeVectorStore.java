package com.insightflow.knowledge;

import com.github.f4b6a3.uuid.UuidCreator;
import com.insightflow.entity.KnowledgeDocumentType;
import java.util.List;
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
        String sql = "INSERT INTO knowledge_chunk (public_id, version_id, chunk_no, content, embedding, token_count) "
                + "VALUES (?, ?, ?, ?, CAST(? AS vector), ?)";
        jdbcTemplate.batchUpdate(sql, chunks, chunks.size(), (statement, chunk) -> {
            statement.setObject(1, UuidCreator.getTimeOrdered());
            statement.setLong(2, versionId);
            statement.setInt(3, chunk.chunkNo());
            statement.setString(4, chunk.content());
            statement.setString(5, vectorLiteral(chunk.embedding()));
            statement.setInt(6, chunk.tokenCount());
        });
    }

    /**
     * 在数据库内完成 FTS、pgvector 与固定 RRF 融合。
     * 查询词仅作为参数进入 {@code websearch_to_tsquery}，文档类型也只来自服务端枚举计划。
     */
    @Override
    public List<SearchCandidate> search(Long organizationId, Long workspaceId, String query,
            List<KnowledgeDocumentType> types, List<Double> queryEmbedding, int limit) {
        String typeFilter = types.isEmpty() ? "" : " AND d.document_type IN (:types)";
        String visibleFilter = "WHERE d.organization_id = :organizationId "
                + "AND (d.target_workspace_id IS NULL OR d.target_workspace_id = :workspaceId) "
                + "AND v.status = 'PUBLISHED'" + typeFilter;
        String sql = "WITH visible AS ("
                + "SELECT c.public_id chunk_id, c.content, c.content_tsv, d.public_id document_id, "
                + "v.public_id version_id, v.version_no, d.title "
                + "FROM knowledge_chunk c JOIN knowledge_document_version v ON v.id = c.version_id "
                + "JOIN knowledge_document d ON d.id = v.document_id " + visibleFilter + "), "
                + "lexical AS (SELECT chunk_id, row_number() OVER (ORDER BY ts_rank_cd(content_tsv, "
                + "websearch_to_tsquery('simple', :query)) DESC) rank_no FROM visible "
                + "WHERE content_tsv @@ websearch_to_tsquery('simple', :query) LIMIT 32), "
                + "semantic AS (SELECT c.public_id chunk_id, row_number() OVER (ORDER BY c.embedding <=> "
                + "CAST(:vector AS vector)) rank_no FROM knowledge_chunk c "
                + "JOIN knowledge_document_version v ON v.id = c.version_id "
                + "JOIN knowledge_document d ON d.id = v.document_id " + visibleFilter + " LIMIT 32) "
                + "SELECT visible.*, COALESCE(1.0 / (60 + lexical.rank_no), 0) "
                + "+ COALESCE(1.0 / (60 + semantic.rank_no), 0) score FROM visible "
                + "LEFT JOIN lexical USING(chunk_id) LEFT JOIN semantic USING(chunk_id) "
                + "WHERE lexical.chunk_id IS NOT NULL OR semantic.chunk_id IS NOT NULL "
                + "ORDER BY score DESC LIMIT :limit";
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("workspaceId", workspaceId)
                .addValue("query", query)
                .addValue("vector", vectorLiteral(queryEmbedding))
                .addValue("limit", limit);
        if (!types.isEmpty()) {
            parameters.addValue("types", types.stream().map(Enum::name).toList());
        }
        return namedJdbcTemplate.query(sql, parameters, (resultSet, rowNumber) -> new SearchCandidate(
                resultSet.getObject("document_id", java.util.UUID.class),
                resultSet.getObject("version_id", java.util.UUID.class),
                resultSet.getInt("version_no"),
                resultSet.getObject("chunk_id", java.util.UUID.class),
                resultSet.getString("title"), resultSet.getString("content"), resultSet.getDouble("score")));
    }

    /** pgvector 使用方括号字面量；数值只来自嵌入模型，绝不把用户原文拼入 SQL。 */
    private String vectorLiteral(List<Double> embedding) {
        return embedding.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }
}
