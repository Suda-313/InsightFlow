import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const workspaceStore = await readFile(new URL('../src/stores/workspace.js', import.meta.url), 'utf8')
const appView = await readFile(new URL('../src/App.vue', import.meta.url), 'utf8')

test('workspace store creates and selects the first workspace through the protected API', () => {
  assert.match(workspaceStore, /const workspaces = ref\(\[\]\)/)
  assert.match(workspaceStore, /async function createWorkspace\(name\)/)
  assert.match(workspaceStore, /fetch\('\/api\/v1\/workspaces', \{\s*method: 'POST'/)
  assert.match(workspaceStore, /body: JSON\.stringify\(\{ name \}\)/)
  assert.match(workspaceStore, /workspaceId\.value = workspace\.publicId/)
})

test('sidebar offers first-workspace creation instead of leaving business pages permanently disabled', () => {
  assert.match(appView, /v-if="!store\.workspaceId"/)
  assert.match(appView, /@submit\.prevent="createWorkspace"/)
  assert.match(appView, /workspaceError/)
  assert.match(appView, /store\.createWorkspace\(workspaceName\.value\.trim\(\)\)/)
})
