<template>
  <div class="p-6">
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-2xl font-bold">分析报告</h1>
      <button @click="createReport" :disabled="creating" class="btn-primary flex items-center gap-2"><Sparkles class="w-4 h-4" /> {{ creating ? '创建中...' : '生成报告' }}</button>
    </div>
    <div v-if="reports.length" class="space-y-3">
      <router-link v-for="r in reports" :key="r.id" :to="'/reports/' + r.id" class="card p-4 flex items-center justify-between hover:shadow-md transition cursor-pointer no-underline text-inherit">
        <div>
          <span class="font-medium">{{ r.id?.slice(0,8) }}</span>
          <span class="ml-3 text-xs px-2 py-0.5 rounded-full" :class="r.status === 'succeeded' ? 'bg-green-100 text-green-700' : r.status === 'failed' ? 'bg-red-100 text-red-700' : 'bg-amber-100 text-amber-700'">{{ r.status }}</span>
        </div>
        <div class="text-xs text-slate-400">{{ r.created_at?.slice(0,10) }}</div>
      </router-link>
    </div>
    <div v-else class="card p-6 text-center">
      <FileText class="w-12 h-12 mx-auto text-slate-300 mb-3" />
      <p class="text-slate-500 mb-4">点击"生成报告"创建 AI 分析报告</p>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { Sparkles, FileText } from 'lucide-vue-next'
import { useWorkspaceStore } from '../stores/workspace'
const store = useWorkspaceStore()
const reports = ref([]), creating = ref(false)

async function load() {
  if (!store.workspaceId) return
  reports.value = [] // API not yet available for listing
}

async function createReport() {
  creating.value = true
  let r = await fetch('/api/v1/workspaces/' + store.workspaceId + '/analysis-reports', {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': 'rpt-' + Date.now() },
    body: JSON.stringify({ time_range: { start: '2026-07-14T00:00:00Z', end: '2026-07-22T00:00:00Z' } })
  })
  let d = await r.json()
  creating.value = false
  alert('报告已创建！任务ID: ' + d.id + '\n请稍后查看。')
}

onMounted(load)
</script>
