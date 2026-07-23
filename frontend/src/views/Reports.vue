<template>
  <div class="p-6">
    <div class="flex items-center justify-between mb-6">
      <div><h1 class="text-2xl font-bold">分析报告</h1><p class="text-sm text-slate-500 mt-1">{{ reports.length }} 份报告</p></div>
      <button @click="createReport" :disabled="creating" class="inline-flex items-center gap-2 px-5 py-2.5 bg-gradient-to-r from-primary to-primary-light text-white rounded-xl font-medium hover:shadow-lg transition disabled:opacity-50">
        <Sparkles class="w-4 h-4" />{{ creating ? '创建中...' : '生成新报告' }}
      </button>
    </div>

    <div v-if="loading" class="space-y-3"><div v-for="i in 3" :key="i" class="card p-4 animate-pulse"><div class="h-4 bg-slate-200 dark:bg-slate-700 rounded w-48 mb-2"></div><div class="h-3 bg-slate-200 dark:bg-slate-700 rounded w-24"></div></div></div>

    <div v-else-if="!reports.length" class="text-center py-20">
      <div class="w-20 h-20 mx-auto mb-6 rounded-2xl bg-gradient-to-br from-amber-100 to-amber-200 dark:from-amber-900 dark:to-amber-800 flex items-center justify-center"><FileText class="w-10 h-10 text-amber-600" /></div>
      <h2 class="text-xl font-semibold mb-2">暂无报告</h2>
      <p class="text-slate-500 mb-6">点击"生成新报告"创建 AI 分析报告</p>
      <button @click="createReport" :disabled="creating" class="btn-accent inline-flex items-center gap-2"><Sparkles class="w-4 h-4" />生成报告</button>
    </div>

    <div v-else class="space-y-3">
      <div v-for="r in reports" :key="r.id" class="card p-5 hover:shadow-lg transition-all duration-200">
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-4">
            <div class="w-10 h-10 rounded-xl bg-gradient-to-br from-amber-100 to-amber-200 dark:from-amber-900 dark:to-amber-800 flex items-center justify-center"><FileText class="w-5 h-5 text-amber-600" /></div>
            <div>
              <div class="font-semibold">{{ reportName(r) }}</div>
              <div class="text-xs text-slate-400 mt-0.5">{{ r.created_at?.slice(0,16) }}</div>
            </div>
          </div>
          <div class="flex items-center gap-3">
            <span class="px-2.5 py-1 text-xs rounded-full font-medium" :class="r.status === 'succeeded' ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/50 dark:text-emerald-300' : r.status === 'failed' ? 'bg-red-100 text-red-700 dark:bg-red-900/50 dark:text-red-300' : 'bg-amber-100 text-amber-700 dark:bg-amber-900/50 dark:text-amber-300'">{{ statusText(r.status) }}</span>
            <router-link v-if="r.status === 'succeeded'" :to="'/reports/' + r.id" class="flex items-center gap-1.5 text-sm text-primary hover:underline"><Eye class="w-4 h-4" />查看</router-link>
            <a v-if="r.status === 'succeeded'" :href="'/api/v1/workspaces/' + store.workspaceId + '/analysis-reports/' + r.id + '/download'" class="flex items-center gap-1.5 text-sm text-primary hover:underline"><Download class="w-4 h-4" />下载</a>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { Sparkles, FileText, Eye, Download, FileDown } from 'lucide-vue-next'
import { useWorkspaceStore } from '../stores/workspace'
const store = useWorkspaceStore()
const reports = ref([]), loading = ref(true), creating = ref(false)

function reportName(r) {
  const date = (r.created_at || '').slice(0, 10)
  const suffix = (r.id || '').slice(0, 6)
  return `运营周报_${date}_${suffix}`
}

function statusText(s) { return s === 'succeeded' ? '已完成' : s === 'failed' ? '失败' : s === 'running' ? '处理中' : '排队中' }

async function load() {
  if (!store.workspaceId) return; loading.value = true
  try {
    let r = await fetch('/api/v1/workspaces/' + store.workspaceId + '/analysis-reports')
    let d = await r.json()
    reports.value = Array.isArray(d) ? d : []
  } catch (e) { console.error(e) }
  loading.value = false
}

async function createReport() {
  creating.value = true
  await fetch('/api/v1/workspaces/' + store.workspaceId + '/analysis-reports', {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': 'rpt-' + Date.now() },
    body: JSON.stringify({ time_range: { start: '2026-07-08T00:00:00Z', end: '2026-07-22T00:00:00Z' } })
  })
  creating.value = false
  setTimeout(load, 3000)
}
onMounted(load)
watch(() => store.workspaceId, load)
</script>