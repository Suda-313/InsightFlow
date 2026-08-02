import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useWorkspaceStore = defineStore('workspace', () => {
  // The visible list is always supplied by the authenticated backend API.
  const workspaces = ref([])

  // Business pages use only this public identifier for workspace-scoped URLs.
  const workspaceId = ref('')

  // Consumers use this state to distinguish loading from an intentional empty list.
  const loading = ref(false)

  // Refreshing never creates a workspace: creation remains an explicit Owner action.
  async function init() {
    loading.value = true
    try {
      const response = await fetch('/api/v1/workspaces')
      const list = await response.json()
      workspaces.value = Array.isArray(list) ? list : []

      // Existing behavior keeps the newest visible workspace selected.
      if (workspaces.value.length > 0) {
        workspaceId.value = workspaces.value[0].publicId || workspaces.value[0].id
        return
      }

      // An empty authenticated result is a valid first-Owner state.
      workspaceId.value = ''
    } catch (error) {
      // A failed list request must not leave a stale workspace selected.
      workspaces.value = []
      workspaceId.value = ''
    } finally {
      loading.value = false
    }
  }

  // Only the protected backend command can authorize and persist a workspace.
  async function createWorkspace(name) {
    const response = await fetch('/api/v1/workspaces', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name })
    })

    // Error payloads are optional, so malformed responses still yield a usable message.
    const workspace = await response.json().catch(() => null)
    if (!response.ok) throw new Error(workspace?.error?.message || 'Unable to create workspace')

    // Only a successful response is allowed to select the new workspace.
    workspaces.value = [workspace, ...workspaces.value]
    workspaceId.value = workspace.publicId
    return workspace
  }

  return { workspaces, workspaceId, loading, init, createWorkspace }
})
