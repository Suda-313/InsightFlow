import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const evaluationView = await readFile(new URL('../src/views/Evaluations.vue', import.meta.url), 'utf8')

test('evaluation page exposes workspace-scoped RAG run and history endpoints', () => {
  assert.match(evaluationView, /\/evaluations\/rag/)
  assert.match(evaluationView, /runRagEvaluation/)
  assert.match(evaluationView, /ragRuns/)
})

test('evaluation page shows all three RAG quality metrics without model answers', () => {
  assert.match(evaluationView, /retrievalRecallRate/)
  assert.match(evaluationView, /citationCorrectnessRate/)
  assert.match(evaluationView, /ungroundedAnswerRate/)
  assert.match(evaluationView, /latestRagRun/)
  assert.match(evaluationView, /run\.metrics/)
  assert.doesNotMatch(evaluationView, /rawModelAnswer/)
})
