import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const importView = await readFile(new URL('../src/views/Import.vue', import.meta.url), 'utf8')

/** 从 Import.vue 源码中提取 autoMapImportHeaders，便于单元测试映射行为。 */
function extractAutoMapFn(source) {
  // Git may check Vue files out with CRLF on Windows; parse source independent of that detail.
  const normalizedSource = source.replace(/\r\n/g, '\n')
  const fnStart = normalizedSource.indexOf('function autoMapImportHeaders(headerList)')
  assert.ok(fnStart >= 0, 'autoMapImportHeaders must exist in Import.vue')
  const fnBody = normalizedSource.slice(fnStart)
  const fnEnd = fnBody.indexOf('\n}\n', fnBody.indexOf('return result'))
  const fnSource = fnBody.slice(0, fnEnd + 3)
  const CANONICAL_IMPORT_KEYS = ['feedback_text', 'occurred_at', 'source', 'external_ref']
  return new Function('CANONICAL_IMPORT_KEYS', fnSource + '\nreturn autoMapImportHeaders')(CANONICAL_IMPORT_KEYS)
}

const autoMapImportHeaders = extractAutoMapFn(importView)

test('import page defines canonical CSV header auto-mapping', () => {
  assert.match(importView, /CANONICAL_IMPORT_KEYS/)
  assert.match(importView, /function autoMapImportHeaders/)
  assert.match(importView, /h\.trim\(\)/)
  assert.match(importView, /includes\('反馈'\)/)
})

test('canonical CSV v1 headers map all four fields and skip manual step', () => {
  const headers = ['feedback_text', 'occurred_at', 'source', 'external_ref']
  const mapping = autoMapImportHeaders(headers)
  assert.deepEqual(mapping, {
    feedback_text: 'feedback_text',
    occurred_at: 'occurred_at',
    source: 'source',
    external_ref: 'external_ref',
  })
  assert.equal(Object.values(mapping).filter(Boolean).length, 4)
})

test('trimmed canonical headers match case-sensitively', () => {
  const mapping = autoMapImportHeaders([' feedback_text ', 'occurred_at', 'source', 'external_ref'])
  assert.equal(mapping.feedback_text, ' feedback_text ')
  assert.equal(Object.values(mapping).filter(Boolean).length, 4)
})

test('non-canonical casing does not auto-map via canonical path', () => {
  const mapping = autoMapImportHeaders(['Feedback_text', 'occurred_at', 'source', 'external_ref'])
  assert.equal(mapping.feedback_text, undefined)
  assert.equal(Object.values(mapping).filter(Boolean).length, 3)
})

test('Chinese keyword fallback fills unmapped columns', () => {
  const mapping = autoMapImportHeaders(['用户反馈', '发生时间', '数据来源', '工单编号'])
  assert.deepEqual(mapping, {
    feedback_text: '用户反馈',
    occurred_at: '发生时间',
    source: '数据来源',
    external_ref: '工单编号',
  })
})

test('canonical match takes priority over Chinese keywords', () => {
  const mapping = autoMapImportHeaders(['feedback_text', 'occurred_at', 'source', 'external_ref', '反馈备注'])
  assert.equal(mapping.feedback_text, 'feedback_text')
  assert.equal(Object.values(mapping).filter(Boolean).length, 4)
})
