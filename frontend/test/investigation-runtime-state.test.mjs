import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const investigationView = await readFile(new URL('../src/views/Investigations.vue', import.meta.url), 'utf8')
const appView = await readFile(new URL('../src/App.vue', import.meta.url), 'utf8')
const router = await readFile(new URL('../src/router/index.js', import.meta.url), 'utf8')

test('调查中心是唯一入口，并使用当前工作区的受控接口', () => {
  assert.match(router, /path:\s*'\/investigations'/)
  assert.match(appView, /to="\/investigations"/)
  assert.match(investigationView, /\/api\/v1\/workspaces\/\$\{store\.workspaceId\}\/investigations/)
})

test('调查中心显式保存预览、执行、撤销和纠错的运行状态', () => {
  assert.match(investigationView, /proposalRunning/)
  assert.match(investigationView, /executions\.value = data\.executions \|\| \[\]/)
  assert.match(investigationView, /Idempotency-Key/)
  assert.match(investigationView, /\/undo/)
  assert.match(investigationView, /\/corrections/)
})

test('调查中心展示加载、空态和失败状态，而不是把权限错误伪装成空数据', () => {
  assert.match(investigationView, /v-if="loading"/)
  assert.match(investigationView, /v-else-if="error"/)
  assert.match(investigationView, /暂无待办调查/)
})

test('风险优先队列展示冻结等级和依据，并可进入调查处理', () => {
  assert.match(investigationView, /riskQueue/)
  assert.match(investigationView, /risk-queue/)
  assert.match(investigationView, /priorityText/)
  assert.match(investigationView, /openRisk/)
})

test('调查卡片支持开始跟进，并明确展示站内超时提醒', () => {
  assert.match(investigationView, /followUpRunning/)
  assert.match(investigationView, /\/follow-up/)
  assert.match(investigationView, /开始跟进/)
  assert.match(investigationView, /followUpReminderAt/)
})
