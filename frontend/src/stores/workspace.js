import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useWorkspaceStore = defineStore('workspace', () => {
  const workspaceId = ref(localStorage.getItem('wsid') || '')

  async function init() {
    if (workspaceId.value) return
    try {
      let r = await fetch('/api/v1/workspaces', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: '默认工作区' }) })
      let d = await r.json()
      workspaceId.value = d.publicId
      localStorage.setItem('wsid', d.publicId)
    } catch (e) { console.error('Workspace init failed:', e) }
  }

  return { workspaceId, init }
})
