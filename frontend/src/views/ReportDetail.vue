<template>
  <div class="p-6">
    <router-link to="/reports" class="text-sm text-primary hover:underline mb-4 inline-flex items-center gap-1"><ArrowLeft class="w-4 h-4" />返回报告列表</router-link>
    <div v-if="loading" class="flex items-center justify-center py-20"><div class="w-8 h-8 border-2 border-primary border-t-transparent rounded-full animate-spin"></div></div>
    <div v-else-if="error" class="card p-8 text-center mt-4"><AlertTriangle class="w-10 h-10 mx-auto text-red-400 mb-2" /><p class="text-red-500">{{ error }}</p></div>
    <div v-else class="mt-4">
      <div class="flex items-center justify-between mb-6">
        <div>
          <h1 class="text-xl font-bold">{{ reportName }}</h1>
          <p class="text-sm text-slate-500 mt-1">{{ report.created_at?.slice(0,16) }}</p>
        </div>
        <a :href="downloadUrl" class="inline-flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-primary to-primary-light text-white rounded-xl font-medium hover:shadow-lg transition"><Download class="w-4 h-4" />下载 Markdown</a>
      </div>
      <div class="flex items-center gap-3 mb-6">
        <span class="px-2.5 py-1 text-xs rounded-full font-medium" :class="report.status === 'succeeded' ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/50 dark:text-emerald-300' : 'bg-red-100 text-red-700 dark:bg-red-900/50 dark:text-red-300'">{{ report.status === 'succeeded' ? '已完成' : '失败' }}</span>
        <span v-if="report.errorCode" class="text-xs text-red-500">{{ report.errorCode }}: {{ report.errorMessage }}</span>
      </div>
      <div v-if="reportContent" class="card p-8 max-w-4xl">
        <div class="prose prose-slate dark:prose-invert max-w-none text-sm leading-relaxed" v-html="renderedContent"></div>
      </div>
      <div v-else-if="report.status === 'succeeded'" class="card p-8 text-center text-slate-400">报告内容为空</div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Download, ArrowLeft, AlertTriangle } from 'lucide-vue-next'
import { useWorkspaceStore } from '../stores/workspace'
const store = useWorkspaceStore()
const route = useRoute()
const report = ref({}), loading = ref(true), error = ref('')
const reportContent = ref(''), downloadUrl = ref('')

const reportName = computed(() => {
  const date = (report.value.created_at || '').slice(0, 10)
  const suffix = (report.value.id || '').slice(0, 6)
  return `运营周报_${date}_${suffix}`
})

const renderedContent = computed(() => {
  if (!reportContent.value) return ''
  return reportContent.value
    .replace(/\*\*(.*?)\*\*/g, '<strong class="font-semibold">$1</strong>')
    .replace(/\n\n/g, '</p><p class="mb-3">')
    .replace(/^### (.*$)/gm, '<h3 class="text-base font-bold mt-5 mb-2 text-primary">$1</h3>')
    .replace(/^## (.*$)/gm, '<h2 class="text-lg font-bold mt-6 mb-3 text-primary-dark">$1</h2>')
    .replace(/^- (.*$)/gm, '<li class="ml-4 mb-1 list-disc">$1</li>')
    .replace(/^(\d+\.) (.*$)/gm, '<li class="ml-4 mb-1 list-decimal">$2</li>')
    .replace(/^<li/, '<ul class="mb-3"><li')
    .replace(/<\/li>\n(?!<li)/g, '</li></ul>')
})

async function load() {
  if (!store.workspaceId) { setTimeout(load, 200); return }
  let id = route.params.id
  downloadUrl.value = '/api/v1/workspaces/' + store.workspaceId + '/analysis-reports/' + id + '/download'
  loading.value = true; error.value = ''
  try {
    let r = await fetch('/api/v1/workspaces/' + store.workspaceId + '/analysis-reports/' + id)
    let d = await r.json()
    if (d.error) { error.value = d.error.message; loading.value = false; return }
    report.value = d
    if (d.report) {
      try { reportContent.value = JSON.parse(d.report).report || '' } catch { reportContent.value = d.report }
    }
  } catch (e) { error.value = '加载失败: ' + e.message }
  loading.value = false
}

onMounted(load)
watch(() => store.workspaceId, (v) => { if (v) load() })
</script>