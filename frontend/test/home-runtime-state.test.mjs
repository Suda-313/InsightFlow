import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

// Home mounts immediately and quick actions can run before any chat response.
// These declarations therefore must remain local reactive state, rather than
// accidental unresolved identifiers introduced by future store refactors.
const homeView = await readFile(new URL('../src/views/Home.vue', import.meta.url), 'utf8')
const appView = await readFile(new URL('../src/App.vue', import.meta.url), 'utf8')
const router = await readFile(new URL('../src/router/index.js', import.meta.url), 'utf8')

test('Home declares state required by dashboard loading and quick actions', () => {
  assert.match(homeView, /const store = useWorkspaceStore\(\)/)
  assert.match(homeView, /const loading = ref\(false\), isEmpty = ref\(false\)/)
})

test('Home restores workspace-scoped chat sessions instead of retaining only browser memory', () => {
  assert.match(homeView, /chatStore\.restore\(store\.workspaceId\)/)
  assert.match(homeView, /chatStore\.send\(store\.workspaceId, text\)/)
  assert.match(homeView, /chatStore\.archiveAndStartNew\(store\.workspaceId\)/)
})

test('CSV import entries navigate to the dedicated import route', () => {
  assert.match(router, /path:\s*'\/import'/)
  assert.doesNotMatch(appView, /\/\?import=1/)
  assert.doesNotMatch(homeView, /\/\?import=1/)
})
