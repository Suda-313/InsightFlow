/** 知识库批量上传：解析 Markdown YAML front matter 与构造 FormData。 */

const DOCUMENT_TYPES = new Set([
  'RELEASE_NOTE',
  'KNOWN_ISSUE',
  'SUPPORT_SOP',
  'SENTIMENT_PLAYBOOK',
  'OPERATION_EVENT',
  'POSTMORTEM',
])

/** 从文件头 --- ... --- 块读取简单 key: value 行（不引入 YAML 依赖）。 */
export function parseFrontMatter(text) {
  const match = String(text || '').match(/^---\r?\n([\s\S]*?)\r?\n---/)
  if (!match) return {}
  const result = {}
  for (const line of match[1].split('\n')) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#')) continue
    const separator = trimmed.indexOf(':')
    if (separator <= 0) continue
    const key = trimmed.slice(0, separator).trim()
    let value = trimmed.slice(separator + 1).trim()
    if (value === 'null' || value === '') value = null
    else if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1)
    }
    result[key] = value
  }
  return result
}

/** 无 front matter 时从文件名推导标题。 */
export function titleFromFilename(filename) {
  const base = String(filename || '').replace(/\\/g, '/').split('/').pop() || '未命名文档'
  return base.replace(/\.(md|markdown|txt)$/i, '').replace(/^超自然行动组-?/, '').trim() || base
}

export function resolveDocumentType(frontMatter, fallback) {
  const candidate = frontMatter.document_type || frontMatter.planned_document_type || fallback
  return DOCUMENT_TYPES.has(candidate) ? candidate : fallback
}

export function buildUploadFormData(file, defaults, frontMatter) {
  const form = new FormData()
  form.append('title', frontMatter.title || defaults.title || titleFromFilename(file.name))
  form.append('type', resolveDocumentType(frontMatter, defaults.type))
  form.append('scope', defaults.scope)
  form.append('file', file)
  appendOptional(form, 'sourceUrl', frontMatter.source_url)
  appendOptional(form, 'owner', frontMatter.owner)
  appendOptional(form, 'factBoundary', frontMatter.fact_boundary)
  appendOptional(form, 'effectiveFrom', frontMatter.effective_from)
  appendOptional(form, 'effectiveTo', frontMatter.effective_to)
  return form
}

function appendOptional(form, field, value) {
  if (value != null && String(value).trim() !== '') {
    form.append(field, String(value).trim())
  }
}
