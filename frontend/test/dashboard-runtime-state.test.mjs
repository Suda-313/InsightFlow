import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const dashboardView = await readFile(new URL('../src/views/Dashboard.vue', import.meta.url), 'utf8')
const dataView = await readFile(new URL('../src/views/Data.vue', import.meta.url), 'utf8')
const analysisRange = await readFile(new URL('../src/components/AnalysisDateRange.vue', import.meta.url), 'utf8')
const analysisWindow = await readFile(new URL('../src/lib/analysis-window.js', import.meta.url), 'utf8')
const router = await readFile(new URL('../src/router/index.js', import.meta.url), 'utf8')

test('Dashboard route is registered', () => {
  assert.match(router, /path:\s*'\/dashboard'/)
})

test('Dashboard loads L2 expression summary and drill-down APIs', () => {
  assert.match(dashboardView, /\/dashboard/)
  assert.match(dashboardView, /expressionSummary|expressionDistribution/)
  assert.match(dashboardView, /\/expressions\/.*\/topics/)
  assert.match(dashboardView, /\/topics\/.*\/samples/)
})

test('Dashboard loads alert_eligible supplementary sub-panel', () => {
  assert.match(dashboardView, /alert-eligible|alertEligible/)
  assert.match(dashboardView, /alert_eligible|可行动议题/)
  assert.match(dashboardView, /alertEligibleLoading|alertEligibleError/)
})

test('Dashboard supports workspace topic pack switching', () => {
  assert.match(dashboardView, /\/topic-packs/)
  assert.match(dashboardView, /\/topic-pack/)
  assert.match(dashboardView, /method:\s*'PUT'/)
})

test('Dashboard handles loading and error states', () => {
  assert.match(dashboardView, /loading\.value/)
  assert.match(dashboardView, /loadError/)
  assert.match(dashboardView, /isEmpty/)
})

test('Dashboard and Data share analysis date range controls', () => {
  assert.match(dashboardView, /AnalysisDateRange/)
  assert.match(dataView, /AnalysisDateRange/)
  assert.match(analysisRange, /分析范围/)
  assert.match(analysisWindow, /analysisQuery/)
  assert.match(dashboardView, /analysisQuery|windowQuery/)
  assert.match(dataView, /analysisQuery|windowQuery/)
})
