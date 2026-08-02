import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { analysisQuery, defaultWindowFromCoverage } from '../src/lib/analysis-window.js'

const analysisWindow = await readFile(new URL('../src/lib/analysis-window.js', import.meta.url), 'utf8')

test('defaultWindowFromCoverage anchors to coverage end minus seven days', () => {
  const result = defaultWindowFromCoverage('2026-06-27T00:00:00Z', '2026-07-11T00:00:00Z')
  assert.equal(result.to, '2026-07-11')
  assert.equal(result.from, '2026-07-04')
})

test('analysisQuery encodes from and to', () => {
  assert.equal(analysisQuery('2026-07-01', '2026-07-11'), '?from=2026-07-01&to=2026-07-11')
  assert.equal(analysisQuery('', ''), '')
})

test('analysis window helper file exports query builder', () => {
  assert.match(analysisWindow, /export function analysisQuery/)
})
