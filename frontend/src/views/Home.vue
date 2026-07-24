<template>
  <div class="flex h-full">
    <!-- AI Chat Panel (Left 40%) -->
    <div class="w-2/5 border-r border-slate-200 dark:border-slate-700 flex flex-col bg-white dark:bg-slate-850">
      <div class="p-4 border-b border-slate-200 dark:border-slate-700">
        <h2 class="font-semibold text-sm flex items-center gap-2"><Sparkles class="w-4 h-4 text-primary" /> AI 助手</h2>
      </div>
      <div class="flex-1 overflow-auto p-4 space-y-4" ref="chatRef">
        <div class="card p-4 bg-gradient-to-br from-primary/5 to-primary-light/5">
          <p class="text-sm font-medium mb-2">👋 你好！我是舆情分析助手</p>
          <p class="text-xs text-slate-500 mb-3">{{ contextText }}</p>
          <div class="space-y-1.5">
            <button @click="send('玩法Bug 为什么暴增？')" class="block w-full text-left text-xs px-3 py-2 rounded-lg bg-white dark:bg-slate-800 hover:bg-primary/5 transition">💬 玩法Bug 为什么暴增？</button>
            <button @click="send('生成一份运营周报')" class="block w-full text-left text-xs px-3 py-2 rounded-lg bg-white dark:bg-slate-800 hover:bg-primary/5 transition">💬 生成一份运营周报</button>
            <button @click="send('对比本周和上周的数据变化')" class="block w-full text-left text-xs px-3 py-2 rounded-lg bg-white dark:bg-slate-800 hover:bg-primary/5 transition">💬 对比本周和上周的数据变化</button>
          </div>
        </div>
        <div v-for="(m, i) in messages" :key="i" :class="m.role === 'user' ? 'flex justify-end' : ''">
          <div :class="m.role === 'user' ? 'bg-primary text-white rounded-xl rounded-br-md px-3 py-2 text-sm max-w-[85%]' : 'card p-3 text-sm max-w-[85%]'">
            <div class="flex items-center gap-2 mb-1" v-if="m.role === 'assistant'"><Sparkles class="w-3 h-3 text-primary" /><span class="text-xs text-primary font-medium">AI 助手</span></div>
            <details v-if="m.thinking" class="mb-2">
              <summary class="text-xs text-slate-400 cursor-pointer hover:text-slate-600">💭 思考过程</summary>
              <div class="mt-1 p-2 bg-slate-50 dark:bg-slate-800 rounded text-xs text-slate-500 whitespace-pre-wrap leading-relaxed">{{ m.thinking }}</div>
            </details>
            <div class="whitespace-pre-wrap leading-relaxed">{{ m.content }}</div>
          </div>
        </div>
        <div v-if="loading" class="flex items-center gap-2 text-sm text-slate-400"><div class="w-4 h-4 border-2 border-primary border-t-transparent rounded-full animate-spin"></div>思考中...</div>
      </div>
      <div class="p-3 border-t border-slate-200 dark:border-slate-700">
        <div class="flex gap-2">
          <input v-model="input" @keydown.enter="send()" placeholder="输入问题或拖拽 CSV..." class="flex-1 bg-slate-100 dark:bg-slate-700 rounded-lg px-3 py-2 text-sm border-0 outline-none focus:ring-2 focus:ring-primary/20">
          <button @click="send()" :disabled="!input.trim() || loading" class="btn-primary px-3 py-2"><Send class="w-4 h-4" /></button>
        </div>
      </div>
    </div>

    <!-- Overview Panel (Right 60%) -->
    <div class="flex-1 overflow-auto p-6">
      <div class="flex items-center justify-between mb-6">
        <div><h1 class="text-xl font-bold">数据概览</h1><p class="text-xs text-slate-500 mt-1">{{ coverageText }}</p></div>
        <router-link to="/data" class="text-sm text-primary hover:underline flex items-center gap-1">查看详情 <ArrowRight class="w-3 h-3" /></router-link>
      </div>

      <div v-if="isEmpty" class="card p-12 text-center">
        <Database class="w-12 h-12 mx-auto text-slate-300 mb-3" />
        <p class="text-slate-500 mb-4">暂无数据</p>
        <router-link to="/?import=1" class="btn-primary inline-flex items-center gap-2"><Upload class="w-4 h-4" />导入数据</router-link>
      </div>

      <template v-else>
        <div class="grid grid-cols-4 gap-4 mb-6">
          <div class="card p-4 bg-gradient-to-br from-blue-50 to-blue-100 dark:from-blue-950 dark:to-blue-900 border-blue-200 dark:border-blue-800"><div class="text-xs text-blue-600 mb-1">总工单</div><div class="text-2xl font-bold text-blue-900">{{ data.totalEvents }}</div></div>
          <div class="card p-4 bg-gradient-to-br from-emerald-50 to-emerald-100 dark:from-emerald-950 dark:to-emerald-900 border-emerald-200 dark:border-emerald-800"><div class="text-xs text-emerald-600 mb-1">主题数</div><div class="text-2xl font-bold text-emerald-900">{{ data.issueCount }}</div></div>
          <div class="card p-4" :class="data.alertCount > 0 ? 'bg-gradient-to-br from-red-50 to-red-100 border-red-200' : 'bg-gradient-to-br from-amber-50 to-amber-100 border-amber-200'"><div class="text-xs mb-1" :class="data.alertCount > 0 ? 'text-red-600' : 'text-amber-600'">告警</div><div class="text-2xl font-bold" :class="data.alertCount > 0 ? 'text-red-900' : 'text-amber-900'">{{ data.alertCount }}</div></div>
          <div class="card p-4 bg-gradient-to-br from-violet-50 to-violet-100 dark:from-violet-950 dark:to-violet-900 border-violet-200 dark:border-violet-800"><div class="text-xs text-violet-600 mb-1">状态</div><div class="text-2xl font-bold text-violet-900">{{ data.projectionStatus || '就绪' }}</div></div>
        </div>

        <div class="grid grid-cols-2 gap-6 mb-6">
          <div class="card p-5"><h3 class="font-semibold text-sm mb-3">主题分布 Top 5</h3><canvas ref="barRef" height="180"></canvas></div>
          <div class="card p-5"><h3 class="font-semibold text-sm mb-3">最近告警</h3>
            <div v-if="!data.alerts.length" class="text-sm text-slate-400 py-8 text-center">暂无告警</div>
            <div v-for="a in data.alerts" :key="a.alertId" class="p-3 bg-red-50 dark:bg-red-900/20 rounded-lg mb-2">
              <router-link :to="'/data?issue=' + (a.issueKey || '')" class="text-sm font-medium text-red-700 hover:underline">{{ a.issueName || a.alertId?.slice(0,8) }}</router-link>
              <div class="text-xs text-slate-400 mt-0.5">{{ a.createdAt?.slice(5,16) }} · {{ a.currentCount }} 条</div>
            </div>
          </div>
        </div>

        <div class="card p-5 border-dashed border-2 border-slate-300 dark:border-slate-600 text-center">
          <p class="text-sm text-slate-500 mb-2">拖拽 CSV 文件到此处上传</p>
          <p class="text-xs text-slate-400">或点击 <router-link to="/?import=1" class="text-primary hover:underline">导入 CSV</router-link> 选择文件</p>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import { Sparkles, Send, ArrowRight, Database, Upload } from 'lucide-vue-next'
import { Chart, BarController, CategoryScale, LinearScale, BarElement, Tooltip } from 'chart.js'
import { useWorkspaceStore } from '../stores/workspace'
Chart.register(BarController, CategoryScale, LinearScale, BarElement, Tooltip)

const store = useWorkspaceStore()
const loading = ref(false), isEmpty = ref(false)
const messages = ref([]), input = ref(''), chatRef = ref(null)
const barRef = ref(null)
let barChart = null
const data = ref({ totalEvents: 0, issueCount: 0, alertCount: 0, alerts: [], topIssues: [], projectionStatus: '' })
const coverageText = ref('')
const contextText = computed(() => data.value.totalEvents ? `📊 ${data.value.totalEvents}条 | ${data.value.issueCount}主题 | ${data.value.alertCount}告警` : '暂无数据，请先导入 CSV')

async function loadData() {
  if (!store.workspaceId) return
  let r = await fetch('/api/v1/workspaces/' + store.workspaceId + '/dashboard')
  let d = await r.json()
  if (!d.coverage?.totalEvents) { isEmpty.value = true; return }
  data.value = { totalEvents: d.coverage?.totalEvents || 0, issueCount: d.topIssues?.length || 0, alertCount: d.recentAlerts?.length || 0, alerts: d.recentAlerts || [], topIssues: d.topIssues || [], projectionStatus: d.latestProjection?.status }
  coverageText.value = d.coverage?.windowStart ? `数据覆盖 ${d.coverage.windowStart?.slice(0,10)} ~ ${d.coverage.windowEnd?.slice(0,10)}` : ''

  await nextTick()
  if (barChart) barChart.destroy()
  if (d.topIssues?.length && barRef.value) {
    barChart = new Chart(barRef.value, { type: 'bar', data: { labels: d.topIssues.map(i => i.canonicalName), datasets: [{ data: d.topIssues.map(i => i.feedbackCount), backgroundColor: ['#1E40AF','#3B82F6','#60A5FA','#93C5FD','#BFDBFE'], borderRadius: 4, barThickness: 28 }] }, options: { responsive: true, indexAxis: 'y', plugins: { legend: { display: false } }, scales: { x: { beginAtZero: true } } } })
  }
}

async function send(preset) {
  const text = preset || input.value.trim()
  if (!text || loading.value || !store.workspaceId) return
  messages.value.push({ role: 'user', content: text })
  input.value = ''
  loading.value = true
  messages.value.push({ role: 'assistant', content: '' })
  const idx = messages.value.length - 1
  try {
    const resp = await fetch('/api/v1/workspaces/' + store.workspaceId + '/chat', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: text })
    })
    const fullText = await resp.text()
    try {
      const data = JSON.parse(fullText)
      if (data.thinking) {
        messages.value[idx].thinking = data.thinking
      }
      messages.value[idx].content = data.content || '抱歉，暂时无法回答。'
    } catch {
      messages.value[idx].content = fullText
    }
  } catch (e) {
    messages.value[idx].content = '网络错误: ' + e.message
  }
  loading.value = false
  nextTick(() => { if (chatRef.value) chatRef.value.scrollTop = chatRef.value.scrollHeight })
}

onMounted(loadData)
watch(() => store.workspaceId, loadData)
</script>