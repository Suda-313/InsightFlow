<template>
  <div class="p-6">
    <div class="flex items-center justify-between mb-6">
      <div>
        <h1 class="text-2xl font-bold">仪表盘</h1>
        <p class="text-sm text-slate-500 mt-1">{{ coverageText }}</p>
      </div>
      <div class="flex gap-2">
        <router-link to="/import" class="btn-primary flex items-center gap-2"><Upload class="w-4 h-4" />导入数据</router-link>
        <router-link to="/reports" class="btn-accent flex items-center gap-2"><Sparkles class="w-4 h-4" />生成报告</router-link>
      </div>
    </div>

    <div v-if="loading" class="space-y-6">
      <div class="grid grid-cols-4 gap-4"><div v-for="i in 4" :key="i" class="card p-4 animate-pulse"><div class="h-4 bg-slate-200 dark:bg-slate-700 rounded w-16 mb-2"></div><div class="h-8 bg-slate-200 dark:bg-slate-700 rounded w-12"></div></div></div>
      <div class="grid grid-cols-2 gap-6"><div class="card p-5 animate-pulse"><div class="h-4 bg-slate-200 dark:bg-slate-700 rounded w-24 mb-4"></div><div class="h-48 bg-slate-200 dark:bg-slate-700 rounded"></div></div><div class="card p-5 animate-pulse"><div class="h-4 bg-slate-200 dark:bg-slate-700 rounded w-24 mb-4"></div><div class="h-48 bg-slate-200 dark:bg-slate-700 rounded"></div></div></div>
    </div>

    <div v-else-if="isEmpty" class="card p-12 text-center">
      <Database class="w-16 h-16 mx-auto text-slate-300 mb-4" />
      <h2 class="text-xl font-semibold mb-2">暂无数据</h2>
      <p class="text-slate-500 mb-6">导入 CSV 文件后，系统将自动分析并生成看板数据</p>
      <router-link to="/import" class="btn-primary inline-flex items-center gap-2"><Upload class="w-4 h-4" />开始导入</router-link>
    </div>

    <template v-else>
      <div class="grid grid-cols-4 gap-4 mb-6">
        <div class="card p-4"><div class="flex items-center justify-between mb-1"><div class="text-xs text-slate-500">总工单数</div><MessageSquare class="w-4 h-4 text-primary-light" /></div><div class="text-2xl font-bold">{{ data.totalEvents }}</div></div>
        <div class="card p-4"><div class="flex items-center justify-between mb-1"><div class="text-xs text-slate-500">主题数</div><Tags class="w-4 h-4 text-primary-light" /></div><div class="text-2xl font-bold">{{ data.issueCount }}</div></div>
        <div class="card p-4" :class="{ 'border-red-300': data.alertCount > 0 }"><div class="flex items-center justify-between mb-1"><div class="text-xs text-slate-500">活跃告警</div><Bell class="w-4 h-4" :class="data.alertCount > 0 ? 'text-red-500' : 'text-slate-400'" /></div><div class="text-2xl font-bold" :class="data.alertCount > 0 ? 'text-status-bad' : ''">{{ data.alertCount }}</div></div>
        <div class="card p-4"><div class="flex items-center justify-between mb-1"><div class="text-xs text-slate-500">投影状态</div><CheckCircle class="w-4 h-4 text-green-500" /></div><div class="text-2xl font-bold text-green-600">{{ data.projectionStatus || '就绪' }}</div></div>
      </div>

      <div class="grid grid-cols-2 gap-6 mb-6">
        <div class="card p-5"><div class="flex items-center justify-between mb-3"><h3 class="font-semibold text-sm">主题分布 Top 5</h3></div><canvas ref="barChartRef" height="220"></canvas></div>
        <div class="card p-5"><h3 class="font-semibold mb-3 text-sm">最近告警</h3>
          <div v-if="!data.alerts.length" class="text-sm text-slate-400 py-8 text-center"><ShieldCheck class="w-8 h-8 mx-auto mb-2 text-green-400" />暂无告警</div>
          <div v-for="a in data.alerts" :key="a.alertId" class="flex items-center justify-between p-3 bg-red-50 dark:bg-red-900/20 rounded-lg mb-2"><div class="flex items-center gap-2"><AlertTriangle class="w-4 h-4 text-red-500" /><span class="text-sm">{{ a.alertId?.slice(0,8) }}</span></div><span class="font-mono font-bold text-red-600">{{ a.currentCount }} 条</span></div>
        </div>
      </div>

      <div class="grid grid-cols-2 gap-6">
        <div class="card p-5"><h3 class="font-semibold mb-3 text-sm">基线状态</h3><div class="flex gap-4"><div class="flex-1"><div class="text-xs text-slate-500">已建立</div><div class="text-lg font-bold text-green-600">{{ data.activeBaselines }}</div></div><div class="flex-1"><div class="text-xs text-slate-500">建立中</div><div class="text-lg font-bold text-amber-500">{{ data.buildingBaselines }}</div></div></div></div>
        <div class="card p-5"><h3 class="font-semibold mb-3 text-sm">快捷操作</h3><div class="grid grid-cols-2 gap-2">
          <router-link to="/import" class="flex items-center gap-2 p-3 bg-primary/5 rounded-lg hover:bg-primary/10 transition text-sm"><Upload class="w-4 h-4 text-primary" />导入 CSV</router-link>
          <router-link to="/issues" class="flex items-center gap-2 p-3 bg-primary/5 rounded-lg hover:bg-primary/10 transition text-sm"><Tags class="w-4 h-4 text-primary" />查看主题</router-link>
          <router-link to="/reports" class="flex items-center gap-2 p-3 bg-accent/5 rounded-lg hover:bg-accent/10 transition text-sm"><FileText class="w-4 h-4 text-accent" />生成报告</router-link>
          <a href="/actuator/health" target="_blank" class="flex items-center gap-2 p-3 bg-primary/5 rounded-lg hover:bg-primary/10 transition text-sm"><Activity class="w-4 h-4 text-primary" />系统状态</a>
        </div></div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted, watch, nextTick } from 'vue'
import { Upload, Sparkles, MessageSquare, Tags, Bell, CheckCircle, ShieldCheck, AlertTriangle, Database, Activity, FileText } from 'lucide-vue-next'
import { Chart, BarController, CategoryScale, LinearScale, BarElement, Tooltip } from 'chart.js'
import { useWorkspaceStore } from '../stores/workspace'
Chart.register(BarController, CategoryScale, LinearScale, BarElement, Tooltip)

const store = useWorkspaceStore()
const loading = ref(true), isEmpty = ref(false)
const barChartRef = ref(null)
let barChart = null
const data = ref({ totalEvents: 0, issueCount: 0, alertCount: 0, activeBaselines: 0, buildingBaselines: 0, alerts: [], topIssues: [], projectionStatus: '' })
const coverageText = ref('')

async function load() {
  if (!store.workspaceId) { loading.value = false; return }
  let r = await fetch('/api/v1/workspaces/' + store.workspaceId + '/dashboard')
  let d = await r.json()
  loading.value = false
  if (!d.coverage?.totalEvents) { isEmpty.value = true; return }
  data.value = { totalEvents: d.coverage?.totalEvents || 0, issueCount: d.topIssues?.length || 0, alertCount: d.recentAlerts?.length || 0, activeBaselines: d.baselineStatus?.active || 0, buildingBaselines: d.baselineStatus?.building || 0, alerts: d.recentAlerts || [], topIssues: d.topIssues || [], projectionStatus: d.latestProjection?.status }
  coverageText.value = d.coverage?.windowStart ? `数据覆盖：${d.coverage.windowStart?.slice(0,10)} ~ ${d.coverage.windowEnd?.slice(0,10)} · ${d.coverage.totalEvents} 条工单` : ''

  await nextTick()
  if (barChart) barChart.destroy()
  if (d.topIssues?.length && barChartRef.value) {
    barChart = new Chart(barChartRef.value, {
      type: 'bar', data: { labels: d.topIssues.map(i => i.canonicalName), datasets: [{ data: d.topIssues.map(i => i.feedbackCount), backgroundColor: ['#1E40AF','#3B82F6','#60A5FA','#93C5FD','#BFDBFE'], borderRadius: 4 }] },
      options: { responsive: true, plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true, grid: { color: '#E2E8F0' } }, x: { grid: { display: false } } } }
    })
  }
}

onMounted(load)
watch(() => store.workspaceId, load)
</script>