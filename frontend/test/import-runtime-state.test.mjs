import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const importView = await readFile(new URL('../src/views/Import.vue', import.meta.url), 'utf8')

test('import page restores state from backend latest and result endpoints', () => {
  assert.match(importView, /baseUrl\(\) \+ '\/latest'/)
  assert.match(importView, /\/result/)
  assert.match(importView, /restoreFromBackend/)
  assert.match(importView, /refreshResult/)
})

test('import page polls until projection completes and shows success or failure', () => {
  assert.match(importView, /projection_status/)
  assert.match(importView, /pipelineStatus/)
  assert.match(importView, /startPolling/)
  assert.match(importView, /stopPolling/)
  assert.match(importView, /导入与分析已完成/)
  assert.match(importView, /处理失败/)
})

test('import page survives navigation via onMounted restore not localStorage', () => {
  assert.match(importView, /onMounted\(restoreFromBackend\)/)
  assert.doesNotMatch(importView, /localStorage/)
})
