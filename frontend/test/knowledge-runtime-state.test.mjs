import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const knowledgeView = await readFile(new URL('../src/views/Knowledge.vue', import.meta.url), 'utf8')
const appView = await readFile(new URL('../src/App.vue', import.meta.url), 'utf8')
const router = await readFile(new URL('../src/router/index.js', import.meta.url), 'utf8')

test('knowledge page is reachable from the application navigation', () => {
  assert.match(router, /path:\s*'\/knowledge'/)
  assert.match(appView, /to="\/knowledge"/)
})

test('knowledge page uses current workspace endpoints and exposes only two scopes', () => {
  assert.match(knowledgeView, /store\.workspaceId/)
  assert.match(knowledgeView, /\/knowledge\/documents/)
  assert.match(knowledgeView, /value="ORGANIZATION"/)
  assert.match(knowledgeView, /value="WORKSPACE"/)
  assert.doesNotMatch(knowledgeView, /targetWorkspaceId|target_workspace_id/)
})

test('knowledge page keeps failure visible and offers lifecycle actions plus internal source links', () => {
  assert.match(knowledgeView, /requestError/)
  assert.match(knowledgeView, /downloadSource\(document\.id, version\.id, version\.source_name\)/)
  assert.doesNotMatch(knowledgeView, /<a :href="sourceUrl/)
  assert.match(knowledgeView, /actionError/)
  assert.match(knowledgeView, /runAction\(document\.id, version\.id, 'publish', 'POST'\)/)
  assert.match(knowledgeView, /runAction\(document\.id, version\.id, 'expire', 'POST'\)/)
  assert.match(knowledgeView, /runAction\(document\.id, version\.id, '', 'DELETE'\)/)
  assert.match(knowledgeView, /\/source/)
})

test('knowledge page scopes pending state to one version and releases a stalled request', () => {
  assert.match(knowledgeView, /isVersionPending\(version\.id\)/)
  assert.doesNotMatch(knowledgeView, /:disabled="sourceKey"/)
  assert.doesNotMatch(knowledgeView, /:disabled="actionKey"/)
  assert.match(knowledgeView, /AbortController/)
  assert.match(knowledgeView, /请求超时，请稍后刷新列表确认结果/)
})
