import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useWorkspaceStore = defineStore('workspace', () => {
  const workspaceId = ref(localStorage.getItem('wsid') || '')

  async function init() {
    if (workspaceId.value) return
    try {
      // 优先使用已有工作区
      let r = await fetch('/api/v1/workspaces')
      let data = await r.json()
      // 如果返回的是数组（列表接口），取第一个
      if (Array.isArray(data) && data.length > 0) {
        workspaceId.value = data[0].publicId || data[0].id
        localStorage.setItem('wsid', workspaceId.value)
        return
      }
      // 如果返回的是单个工作区
      if (data.publicId) {
        workspaceId.value = data.publicId
        localStorage.setItem('wsid', workspaceId.value)
        return
      }
      // 从已有 workspace 列表取第一个（兼容不同返回格式）
      if (Array.isArray(data) && data.length > 0 && data[0].publicId) {
        workspaceId.value = data[0].publicId
        localStorage.setItem('wsid', workspaceId.value)
        return
      }
    } catch (e) {}

    // 没有已有工作区，创建新的
    try {
      let r = await fetch('/api/v1/workspaces', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: '默认工作区' }) })
      let d = await r.json()
      if (d.publicId) {
        workspaceId.value = d.publicId
        localStorage.setItem('wsid', d.publicId)
      }
    } catch (e) { console.error('Workspace init failed:', e) }
  }

  return { workspaceId, init }
})