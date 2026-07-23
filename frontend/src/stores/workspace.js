import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useWorkspaceStore = defineStore('workspace', () => {
  const workspaceId = ref('')

  async function init() {
    try {
      let r = await fetch('/api/v1/workspaces')
      let list = await r.json()
      if (Array.isArray(list) && list.length > 0) {
        workspaceId.value = list[0].publicId || list[0].id
        return
      }
    } catch (e) {}
    // 没有工作区则创建
    try {
      let r = await fetch('/api/v1/workspaces', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: '默认工作区' }) })
      let d = await r.json()
      workspaceId.value = d.publicId || d.id
    } catch (e) {}
  }

  return { workspaceId, init }
})