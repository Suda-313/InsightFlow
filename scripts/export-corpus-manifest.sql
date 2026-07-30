-- 导出当前 Workspace 可见且已发布语料的 manifest，供 RAG 金标 seed 证据解析。
-- 用法：docker exec -i yuqiagent-postgres-1 psql -U insightflow -d insightflow -t -A -f - < scripts/export-corpus-manifest.sql

WITH ws AS (
  SELECT id, public_id, name, organization_id FROM workspace WHERE id = 1
),
visible_docs AS (
  SELECT d.*
  FROM knowledge_document d, ws
  WHERE d.organization_id = ws.organization_id
    AND (d.target_workspace_id IS NULL OR d.target_workspace_id = ws.id)
),
doc_versions AS (
  SELECT v.*,
         d.public_id AS document_public_id,
         d.title,
         d.document_type,
         d.target_workspace_id,
         regexp_replace(v.source_name, '\.(md|markdown|txt)$', '', 'i') AS document_ref
  FROM knowledge_document_version v
  JOIN visible_docs d ON d.id = v.document_id
  WHERE v.status = 'PUBLISHED'
),
chunk_rows AS (
  SELECT c.public_id AS chunk_public_id,
         c.version_id,
         c.chunk_no,
         left(
           regexp_replace(
             regexp_replace(c.content, E'[\\n\\r\\t]', ' ', 'g'),
             E'\\u0000', '', 'g'
           ),
           120
         ) AS chunk_preview,
         c.token_count
  FROM knowledge_chunk c
  JOIN doc_versions v ON v.id = c.version_id
)
SELECT jsonb_build_object(
  'exported_at', to_char(timezone('Asia/Shanghai', now()), 'YYYY-MM-DD"T"HH24:MI:SS+08:00'),
  'workspace_public_id', (SELECT public_id FROM ws),
  'workspace_name', (SELECT name FROM ws),
  'source_corpus_version', 'corpus:chaoziran-2026-07-published',
  'statistics', jsonb_build_object(
    'document_count', (SELECT count(*) FROM visible_docs),
    'published_version_count', (SELECT count(*) FROM doc_versions),
    'chunk_count', (SELECT count(*) FROM chunk_rows)
  ),
  'documents', COALESCE((
    SELECT jsonb_agg(doc ORDER BY doc->>'title')
    FROM (
      SELECT jsonb_build_object(
        'document_ref', dv.document_ref,
        'title', dv.title,
        'document_id', dv.document_public_id,
        'document_type', dv.document_type,
        'scope', CASE WHEN dv.target_workspace_id IS NULL THEN 'ORGANIZATION' ELSE 'WORKSPACE' END,
        'versions', (
          SELECT jsonb_agg(jsonb_build_object(
            'version_id', v.public_id,
            'version_no', v.version_no,
            'status', v.status,
            'source_name', v.source_name,
            'source_url', v.source_url,
            'effective_from', v.effective_from,
            'effective_to', v.effective_to,
            'owner', v.owner,
            'fact_boundary', v.fact_boundary,
            'published_at', v.published_at,
            'chunk_count', (SELECT count(*) FROM knowledge_chunk c WHERE c.version_id = v.id),
            'chunks', (
              SELECT jsonb_agg(jsonb_build_object(
                'chunk_id', cr.chunk_public_id,
                'chunk_no', cr.chunk_no,
                'token_count', cr.token_count,
                'preview', cr.chunk_preview
              ) ORDER BY cr.chunk_no)
              FROM chunk_rows cr
              WHERE cr.version_id = v.id
            )
          ) ORDER BY v.version_no)
          FROM doc_versions v
          WHERE v.document_id = dv.document_id
        )
      ) AS doc
      FROM doc_versions dv
      GROUP BY dv.document_id, dv.document_public_id, dv.title, dv.document_type,
               dv.target_workspace_id, dv.document_ref
    ) docs
  ), '[]'::jsonb)
);
