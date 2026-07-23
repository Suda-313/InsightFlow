<template>
  <div class="p-6">
    <router-link to="/reports" class="text-sm text-primary hover:underline mb-4 inline-block">&larr; 返回报告列表</router-link>
    <div v-if="loading" class="text-slate-400 mt-4">加载中...</div>
    <div v-else-if="error" class="text-red-500 mt-4">{{ error }}</div>
    <div v-else class="mt-4">
      <div class="flex items-center justify-between mb-4">
        <h1 class="text-xl font-bold">报告详情</h1>
        <div class="flex gap-2">
          <a :href="downloadUrl" class="btn-primary flex items-center gap-2 text-sm"><Download class="w-4 h-4" /> 下载 Markdown</a>
        </div>
      </div>
      <div class="flex items-center gap-3 mb-6">
        <span class="text-xs px-2 py-0.5 rounded-full" :class="report.status === 'succeeded' ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'">{{ report.status }}</span>
        <span class="text-xs text-slate-400">{{ report.created_at?.slice(0,19) }}</span>
      </div>
      <div v-if="reportContent" class="card p-6 prose prose-sm dark:prose-invert max-w-none" v-html="renderedContent"></div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { Download } from 'lucide-vue-next'
import { useWorkspaceStore } from '../stores/workspace'
const store = useWorkspaceStore()
const route = useRoute()
const report = ref({}), loading = ref(true), error = ref('')
const reportContent = ref(''), downloadUrl = ref('')

const renderedContent = computed(() => {
  return reportContent.value.replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>').replace(/\n/g, '<br>').replace(/^### (.*)/gm, '<h3 class="text-lg font-bold mt-4 mb-2">$1</h3>').replace(/^## (.*)/gm, '<h2 class="text-xl font-bold mt-6 mb-3">$1</h2>')
})

async function load() {
  let id = route.params.id
  downloadUrl.value = '/api/v1/workspaces/' + store.workspaceId + '/analysis-reports/' + id + '/download'
  try {
    let r = await fetch('/api/v1/workspaces/' + store.workspaceId + '/analysis-reports/' + id)
    let d = await r.json()
    report.value = d
    if (d.report) {
      try { reportContent.value = JSON.parse(d.report).report || '' } catch { reportContent.value = d.report }
    }
  } catch (e) { error.value = '加载失败: ' + e.message }
  loading.value = false
}

onMounted(load)
</script>
