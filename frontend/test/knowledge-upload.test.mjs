import assert from 'node:assert/strict'
import test from 'node:test'
import {
  buildUploadFormData,
  parseFrontMatter,
  resolveDocumentType,
  titleFromFilename,
} from '../src/utils/knowledge-upload.js'

test('parseFrontMatter reads yaml header fields', () => {
  const text = `---
title: 测试标题
document_type: POSTMORTEM
effective_from: 2026-07-01T00:00:00+08:00
owner: 舆情组
---

# 正文
`
  const fm = parseFrontMatter(text)
  assert.equal(fm.title, '测试标题')
  assert.equal(fm.document_type, 'POSTMORTEM')
  assert.equal(fm.owner, '舆情组')
})

test('resolveDocumentType prefers document_type over planned_document_type', () => {
  assert.equal(resolveDocumentType({ document_type: 'OPERATION_EVENT' }, 'RELEASE_NOTE'), 'OPERATION_EVENT')
  assert.equal(resolveDocumentType({ planned_document_type: 'POSTMORTEM' }, 'RELEASE_NOTE'), 'POSTMORTEM')
  assert.equal(resolveDocumentType({ document_type: 'INVALID' }, 'KNOWN_ISSUE'), 'KNOWN_ISSUE')
})

test('titleFromFilename strips extension and project prefix', () => {
  assert.equal(titleFromFilename('超自然行动组-1.4.2-热修复说明.md'), '1.4.2-热修复说明')
})

test('buildUploadFormData uses front matter title and type', () => {
  const file = { name: 'demo.md' }
  const form = buildUploadFormData(file, { title: '', type: 'RELEASE_NOTE', scope: 'WORKSPACE' }, {
    title: '来自 YAML',
    document_type: 'OPERATION_EVENT',
    effective_from: '2026-07-10T00:00:00+08:00',
  })
  assert.equal(form.get('title'), '来自 YAML')
  assert.equal(form.get('type'), 'OPERATION_EVENT')
  assert.equal(form.get('scope'), 'WORKSPACE')
  assert.equal(form.get('effectiveFrom'), '2026-07-10T00:00:00+08:00')
})
