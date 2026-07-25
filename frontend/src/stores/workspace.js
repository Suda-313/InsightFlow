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
    // Workspace 只能由有组织权限的后端命令创建；前端没有可见工作区时保持空态而不擅自创建。
    workspaceId.value = ''
  }

  return { workspaceId, init }
})
