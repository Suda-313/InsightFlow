<template>
  <div class="p-6">
    <div class="flex items-center justify-between mb-6">
      <div>
        <h1 class="text-2xl font-bold">仪表盘</h1>
        <p class="text-sm text-slate-500 mt-1">{{ coverageText }}</p>
      </div>
      <router-link to="/reports" class="btn-primary flex items-center gap-2"><Plus class="w-4 h-4" /> 生成报告</router-link>
    </div>

    <div class="grid grid-cols-4 gap-4 mb-6">
      <div class="card p-4"><div class="text-xs text-slate-500 mb-1">总工单数</div><div class="text-2xl font-bold">{{ data.totalEvents }}</div></div>
      <div class="card p-4"><div class="text-xs text-slate-500 mb-1">主题数</div><div class="text-2xl font-bold">{{ data.issueCount }}</div></div>
      <div class="card p-4"><div class="text-xs text-slate-500 mb-1">活跃告警</div><div class="text-2xl font-bold" :class="data.alertCount > 0 ? 'text-status-bad' : ''">{{ data.alertCount }}</div></div>
      <div class="card p-4"><div class="text-xs text-slate-500 mb-1">基线状态</div><div class="text-2xl font-bold">{{ data.activeBaselines }} active</div></div>
    </div>

    <div class="grid grid-cols-2 gap-6 mb-6">
      <div class="card p-5">
        <h3 class="font-semibold mb-3 text-sm">主题分布 Top 5</h3>
        <canvas ref="chartRef" height="200"></canvas>
      </div>
      <div class="card p-5">
        <h3 class="font-semibold mb-3 text-sm">最近告警</h3>
        <div v-if="!data.alerts.length" class="text-sm text-slate-400">暂无告警</div>
        <div v-for="a in data.alerts" :key="a.alertId" class="flex items-center justify-between p-3 bg-red-50 dark:bg-red-900/20 rounded-lg mb-2">
          <div class="flex items-center gap-2"><span class="w-2 h-2 bg-red-500 rounded-full"></span><span class="text-xs">{{ a.alertId?.slice(0,8) }}</span></div>
          <span class="font-mono font-bold text-red-600 text-sm">{{ a.currentCount }} 条</span>
        </div>
      </div>
    </div>

    <div class="card p-5">
      <h3 class="font-semibold mb-3 text-sm">基线状态</h3>
      <span class="text-sm text-slate-500">{{ baselineText }}</span>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { Plus } from 'lucide-vue-next'
import { Chart, BarController, CategoryScale, LinearScale, BarElement } from 'chart.js'
import { useWorkspaceStore } from '../stores/workspace'
Chart.register(BarController, CategoryScale, LinearScale, BarElement)

const store = useWorkspaceStore()
const chartRef = ref(null)
let chart = null
const data = ref({ totalEvents: 0, issueCount: 0, alertCount: 0, alertTotal: 0, activeBaselines: 0, buildingBaselines: 0, alerts: [], topIssues: [], coverageStart: null, coverageEnd: null })
const coverageText = ref('加载中...')
const baselineText = ref('')

async function load() {
  if (!store.workspaceId) return
  let r = await fetch('/api/v1/workspaces/' + store.workspaceId + '/dashboard')
  let d = await r.json()
  data.value = {
    totalEvents: d.coverage?.totalEvents || 0,
    issueCount: d.topIssues?.length || 0,
    alertCount: d.recentAlerts?.length || 0,
    activeBaselines: d.baselineStatus?.active || 0,
    buildingBaselines: d.baselineStatus?.building || 0,
    alerts: d.recentAlerts || [],
    topIssues: d.topIssues || [],
    coverageStart: d.coverage?.windowStart,
    coverageEnd: d.coverage?.windowEnd
  }
  coverageText.value = d.coverage?.windowStart ? `数据覆盖：${d.coverage.windowStart?.slice(0,10)} ~ ${d.coverage.windowEnd?.slice(0,10)} · ${d.coverage.totalEvents} 条工单` : '暂无数据，请先导入 CSV'
  baselineText.value = d.baselineStatus ? `已建立基线：${d.baselineStatus.active} 个主题 active · ${d.baselineStatus.building} 个 building` : '暂无基线数据'

  if (chart) chart.destroy()
  if (d.topIssues?.length && chartRef.value) {
    chart = new Chart(chartRef.value, {
      type: 'bar', data: { labels: d.topIssues.map(i => i.canonicalName), datasets: [{ data: d.topIssues.map(i => i.feedbackCount), backgroundColor: ['#1E40AF','#3B82F6','#60A5FA','#93C5FD','#BFDBFE'] }] },
      options: { responsive: true, plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true } } }
    })
  }
}

onMounted(load)
watch(() => store.workspaceId, load)
</script>
