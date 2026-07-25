<template>
  <div class="flex h-full">
    <!-- AI Chat Panel (Left 40%) -->
    <div class="w-2/5 border-r border-slate-200 dark:border-slate-700 flex flex-col bg-white dark:bg-slate-850">
      <div class="p-4 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between">
        <div class="min-w-0">
          <h2 class="font-semibold text-sm flex items-center gap-2"><Sparkles class="w-4 h-4 text-primary" /> AI 助手</h2>
          <select v-if="sessions.length" v-model="activeSessionId" @change="switchSession" class="mt-1 max-w-44 text-xs text-slate-500 bg-transparent border-0 outline-none">
            <option v-for="session in sessions" :key="session.id" :value="session.id">{{ session.title }}</option>
          </select>
        </div>
        <div class="flex items-center gap-2">
          <button @click="newSession" class="text-xs text-primary hover:underline">新对话</button>
          <button @click="clearSession" class="text-xs text-slate-400 hover:text-slate-600" v-if="messages.length">清空对话</button>
        </div>
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
        <div v-for="m in messages" :key="m.id" :class="m.role === 'user' ? 'flex justify-end' : ''">
          <div :class="m.role === 'user' ? 'bg-primary text-white rounded-xl rounded-br-md px-3 py-2 text-sm max-w-[85%]' : 'card p-3 text-sm max-w-[85%]'">
            <div class="flex items-center justify-between mb-1">
              <div class="flex items-center gap-2" v-if="m.role === 'assistant'"><Sparkles class="w-3 h-3 text-primary" /><span class="text-xs text-primary font-medium">AI 助手</span></div>
              <span class="text-xs text-slate-400">{{ m.time }}</span>
            </div>
            <div class="whitespace-pre-wrap leading-relaxed">{{ m.content }}</div>
            <details v-if="m.role === 'assistant' && m.evidence?.length" class="mt-2 rounded bg-slate-50 dark:bg-slate-800 px-2 py-1.5 text-xs text-slate-500">
              <summary class="cursor-pointer text-primary">查看本次调查证据（{{ m.evidence.length }} 项）</summary>
              <ul class="mt-2 space-y-1">
                <li v-for="item in m.evidence" :key="item.id"><span class="font-medium">{{ item.title }}</span>：{{ item.content }}</li>
              </ul>
            </details>
          </div>
        </div>
        <div v-if="loading" class="flex items-center gap-2 text-sm text-slate-400"><div class="w-4 h-4 border-2 border-primary border-t-transparent rounded-full animate-spin"></div>生成回答中...</div>
        <div v-if="chatError" class="text-xs text-red-500">{{ chatError }}</div>
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
        <router-link to="/import" class="btn-primary inline-flex items-center gap-2"><Upload class="w-4 h-4" />导入数据</router-link>
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
              <router-link to="/investigations" class="text-sm font-medium text-red-700 hover:underline">{{ a.issueName || a.alertId?.slice(0,8) }}</router-link>
              <div class="text-xs text-slate-400 mt-0.5">{{ a.createdAt?.slice(5,16) }} · {{ a.currentCount }} 条</div>
            </div>
          </div>
        </div>

        <div class="card p-5 border-dashed border-2 border-slate-300 dark:border-slate-600 text-center">
          <p class="text-sm text-slate-500 mb-2">拖拽 CSV 文件到此处上传</p>
          <p class="text-xs text-slate-400">或点击 <router-link to="/import" class="text-primary hover:underline">导入 CSV</router-link> 选择文件</p>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import { storeToRefs } from 'pinia'
import { Sparkles, Send, ArrowRight, Database, Upload } from 'lucide-vue-next'
import { Chart, BarController, CategoryScale, LinearScale, BarElement, Tooltip } from 'chart.js'
import { useWorkspaceStore } from '../stores/workspace'
Chart.register(BarController, CategoryScale, LinearScale, BarElement, Tooltip)

import { useChatStore } from '../stores/chat'
const store = useWorkspaceStore()
// 看板与聊天请求必须使用应用启动后选定的同一个工作区。
const loading = ref(false), isEmpty = ref(false)
// loading 防止重复发送；isEmpty 控制看板无数据状态。
const chatStore = useChatStore()
// storeToRefs 保持 Pinia 状态的响应式引用，避免解构后丢失刷新恢复结果。
const { messages, sessions, activeSessionId } = storeToRefs(chatStore)
const input = ref(''), chatRef = ref(null), chatError = ref('')
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
  input.value = ''
  chatError.value = ''
  loading.value = true
  try {
    await chatStore.send(store.workspaceId, text)
  } catch (e) {
    chatError.value = '回答生成失败，请稍后重试。'
  }
  loading.value = false
  nextTick(() => { if (chatRef.value) chatRef.value.scrollTop = chatRef.value.scrollHeight })
}

async function restoreChat() {
  try {
    await chatStore.restore(store.workspaceId)
    await nextTick()
    if (chatRef.value) chatRef.value.scrollTop = chatRef.value.scrollHeight
  } catch (e) {
    chatError.value = '历史对话加载失败，请刷新后重试。'
  }
}

async function switchSession() {
  try {
    await chatStore.selectSession(store.workspaceId, activeSessionId.value)
  } catch (e) {
    chatError.value = '历史对话加载失败，请刷新后重试。'
  }
}

async function newSession() {
  try {
    await chatStore.createSession(store.workspaceId)
  } catch (e) {
    chatError.value = '新建对话失败，请稍后重试。'
  }
}

async function clearSession() {
  try {
    await chatStore.archiveAndStartNew(store.workspaceId)
  } catch (e) {
    chatError.value = '清空对话失败，请稍后重试。'
  }
}

onMounted(() => { loadData(); restoreChat() })
watch(() => store.workspaceId, () => { loadData(); restoreChat() })
</script>
