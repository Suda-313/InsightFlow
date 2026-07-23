<template>
  <div class="p-6">
    <div class="mb-6">
      <h1 class="text-2xl font-bold">仪表盘</h1>
      <p class="text-sm text-slate-500 mt-1">{{ coverageText }}</p>
    </div>

    <div v-if="loading" class="space-y-6">
      <div class="grid grid-cols-4 gap-4"><div v-for="i in 4" :key="i" class="card p-5 animate-pulse"><div class="h-3 bg-slate-200 dark:bg-slate-700 rounded w-16 mb-3"></div><div class="h-8 bg-slate-200 dark:bg-slate-700 rounded w-20"></div></div></div>
      <div class="grid grid-cols-2 gap-6"><div class="card p-5 animate-pulse"><div class="h-4 bg-slate-200 dark:bg-slate-700 rounded w-24 mb-4"></div><div class="h-48 bg-slate-200 dark:bg-slate-700 rounded"></div></div><div class="card p-5 animate-pulse"><div class="h-4 bg-slate-200 dark:bg-slate-700 rounded w-24 mb-4"></div><div class="h-48 bg-slate-200 dark:bg-slate-700 rounded"></div></div></div>
    </div>

    <div v-else-if="isEmpty" class="text-center py-20">
      <div class="w-20 h-20 mx-auto mb-6 rounded-2xl bg-gradient-to-br from-primary to-primary-light flex items-center justify-center"><Database class="w-10 h-10 text-white" /></div>
      <h2 class="text-xl font-semibold mb-2">欢迎使用 InsightFlow</h2>
      <p class="text-slate-500 mb-8 max-w-md mx-auto">导入 CSV 客服工单数据，系统将自动进行主题分类、趋势分析和异常告警</p>
      <router-link to="/import" class="inline-flex items-center gap-2 px-6 py-3 bg-gradient-to-r from-primary to-primary-light text-white rounded-xl font-medium hover:shadow-lg transition"><Upload class="w-5 h-5" />开始导入数据</router-link>
    </div>

    <template v-else>
      <!-- KPI Cards -->
      <div class="grid grid-cols-4 gap-4 mb-6">
        <div class="relative overflow-hidden rounded-xl bg-gradient-to-br from-blue-50 to-blue-100 dark:from-blue-950 dark:to-blue-900 border border-blue-200 dark:border-blue-800 p-5">
          <div class="absolute top-0 right-0 w-20 h-20 -mr-4 -mt-4 rounded-full bg-blue-200/30 dark:bg-blue-700/20"></div>
          <div class="relative"><div class="text-xs font-medium text-blue-600 dark:text-blue-400 mb-1">总工单数</div><div class="text-3xl font-bold text-blue-900 dark:text-blue-100">{{ data.totalEvents }}</div><div class="text-xs text-blue-500/70 mt-1">7 天累计</div></div>
        </div>
        <div class="relative overflow-hidden rounded-xl bg-gradient-to-br from-emerald-50 to-emerald-100 dark:from-emerald-950 dark:to-emerald-900 border border-emerald-200 dark:border-emerald-800 p-5">
          <div class="absolute top-0 right-0 w-20 h-20 -mr-4 -mt-4 rounded-full bg-emerald-200/30 dark:bg-emerald-700/20"></div>
          <div class="relative"><div class="text-xs font-medium text-emerald-600 dark:text-emerald-400 mb-1">识别主题</div><div class="text-3xl font-bold text-emerald-900 dark:text-emerald-100">{{ data.issueCount }}</div><div class="text-xs text-emerald-500/70 mt-1">{{ data.activeBaselines }} 个已建立基线</div></div>
        </div>
        <div class="relative overflow-hidden rounded-xl bg-gradient-to-br from-amber-50 to-amber-100 dark:from-amber-950 dark:to-amber-900 border border-amber-200 dark:border-amber-800 p-5" :class="data.alertCount > 0 ? '!from-red-50 !to-red-100 !border-red-200 dark:!from-red-950 dark:!to-red-900 dark:!border-red-800' : ''">
          <div class="absolute top-0 right-0 w-20 h-20 -mr-4 -mt-4 rounded-full bg-amber-200/30 dark:bg-amber-700/20" :class="data.alertCount > 0 ? '!bg-red-200/30 dark:!bg-red-700/20' : ''"></div>
          <div class="relative"><div class="text-xs font-medium mb-1" :class="data.alertCount > 0 ? 'text-red-600 dark:text-red-400' : 'text-amber-600 dark:text-amber-400'">活跃告警</div><div class="text-3xl font-bold" :class="data.alertCount > 0 ? 'text-red-900 dark:text-red-100' : 'text-amber-900 dark:text-amber-100'">{{ data.alertCount }}</div><div class="text-xs mt-1" :class="data.alertCount > 0 ? 'text-red-500/70' : 'text-amber-500/70'">{{ data.alertCount > 0 ? '⚠ 需要关注' : '一切正常' }}</div></div>
        </div>
        <div class="relative overflow-hidden rounded-xl bg-gradient-to-br from-violet-50 to-violet-100 dark:from-violet-950 dark:to-violet-900 border border-violet-200 dark:border-violet-800 p-5">
          <div class="absolute top-0 right-0 w-20 h-20 -mr-4 -mt-4 rounded-full bg-violet-200/30 dark:bg-violet-700/20"></div>
          <div class="relative"><div class="text-xs font-medium text-violet-600 dark:text-violet-400 mb-1">投影状态</div><div class="text-3xl font-bold text-violet-900 dark:text-violet-100">{{ data.projectionStatus === 'succeeded' ? '✓' : '—' }}</div><div class="text-xs text-violet-500/70 mt-1">{{ data.projectedAt ? data.projectedAt.slice(0,10) : '等待中' }}</div></div>
        </div>
      </div>

      <!-- Charts Row -->
      <div class="grid grid-cols-3 gap-6 mb-6">
        <div class="col-span-2 card p-5">
          <div class="flex items-center justify-between mb-4"><h3 class="font-semibold">主题分布</h3><span class="text-xs text-slate-400">按反馈数排序 Top 5</span></div>
          <canvas ref="barChartRef" height="200"></canvas>
        </div>
        <div class="card p-5">
          <h3 class="font-semibold mb-4">最近告警</h3>
          <div v-if="!data.alerts.length" class="text-center py-10"><ShieldCheck class="w-10 h-10 mx-auto text-emerald-400 mb-2" /><p class="text-sm text-slate-400">暂无告警</p></div>
          <div v-for="a in data.alerts" :key="a.alertId" class="mb-3 p-3 bg-red-50 dark:bg-red-950/50 border border-red-100 dark:border-red-900/50 rounded-lg">
            <div class="flex items-center justify-between mb-1"><router-link :to="'/issues/' + (a.issueKey || '')" class="text-sm font-medium text-red-700 hover:underline">{{ a.issueName || a.alertId?.slice(0,8) }}</router-link><span class="text-xs text-slate-400">{{ a.createdAt?.slice(5,16) }}</span></div>
            <div class="flex items-center justify-between"><span class="text-sm font-medium">{{ a.currentCount }} 条异常反馈</span><span class="px-2 py-0.5 text-xs rounded-full bg-red-100 text-red-700 dark:bg-red-900/50 dark:text-red-300">触发告警</span></div>
          </div>
        </div>
      </div>

      <!-- Bottom Row -->
      <div class="grid grid-cols-3 gap-6">
        <div class="card p-5"><h3 class="font-semibold mb-4">基线状态</h3>
          <div class="space-y-3">
            <div class="flex items-center justify-between"><span class="text-sm text-slate-500">已建立基线</span><span class="text-sm font-bold text-emerald-600">{{ data.activeBaselines }} 个主题</span></div>
            <div class="w-full bg-slate-100 dark:bg-slate-700 rounded-full h-2"><div class="bg-emerald-500 h-2 rounded-full" :style="{ width: (data.activeBaselines / (data.activeBaselines + data.buildingBaselines || 1) * 100) + '%' }"></div></div>
            <div class="flex items-center justify-between"><span class="text-sm text-slate-500">建立中</span><span class="text-sm font-bold text-amber-600">{{ data.buildingBaselines }} 个主题</span></div>
          </div>
        </div>
        <div class="card p-5"><h3 class="font-semibold mb-4">快捷操作</h3>
          <div class="space-y-2">
            <router-link to="/import" class="flex items-center gap-3 p-3 rounded-lg bg-slate-50 dark:bg-slate-800 hover:bg-blue-50 dark:hover:bg-blue-950 transition text-sm"><div class="w-8 h-8 rounded-lg bg-blue-100 dark:bg-blue-900 flex items-center justify-center"><Upload class="w-4 h-4 text-blue-600" /></div>导入 CSV 数据</router-link>
            <router-link to="/issues" class="flex items-center gap-3 p-3 rounded-lg bg-slate-50 dark:bg-slate-800 hover:bg-blue-50 dark:hover:bg-blue-950 transition text-sm"><div class="w-8 h-8 rounded-lg bg-violet-100 dark:bg-violet-900 flex items-center justify-center"><Tags class="w-4 h-4 text-violet-600" /></div>查看主题分析</router-link>
            <router-link to="/reports" class="flex items-center gap-3 p-3 rounded-lg bg-slate-50 dark:bg-slate-800 hover:bg-amber-50 dark:hover:bg-amber-950 transition text-sm"><div class="w-8 h-8 rounded-lg bg-amber-100 dark:bg-amber-900 flex items-center justify-center"><FileText class="w-4 h-4 text-amber-600" /></div>生成分析报告</router-link>
          </div>
        </div>
        <div class="card p-5"><h3 class="font-semibold mb-4">系统信息</h3>
          <div class="space-y-2 text-sm">
            <div class="flex justify-between"><span class="text-slate-500">数据覆盖</span><span class="font-mono text-xs">{{ data.coverageStart?.slice(0,10) || '-' }} ~ {{ data.coverageEnd?.slice(0,10) || '-' }}</span></div>
            <div class="flex justify-between"><span class="text-slate-500">投影状态</span><span class="px-2 py-0.5 text-xs rounded-full" :class="data.projectionStatus === 'succeeded' ? 'bg-emerald-100 text-emerald-700' : 'bg-slate-100 text-slate-500'">{{ data.projectionStatus || 'N/A' }}</span></div>
            <div class="flex justify-between"><span class="text-slate-500">最近投影</span><span class="font-mono text-xs">{{ data.projectedAt?.slice(5,16) || '-' }}</span></div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted, watch, nextTick } from 'vue'
import { Upload, Database, ShieldCheck, Tags, FileText } from 'lucide-vue-next'
import { Chart, BarController, CategoryScale, LinearScale, BarElement, Tooltip } from 'chart.js'
import { useWorkspaceStore } from '../stores/workspace'
Chart.register(BarController, CategoryScale, LinearScale, BarElement, Tooltip)

const store = useWorkspaceStore()
const loading = ref(true), isEmpty = ref(false)
const barChartRef = ref(null)
let barChart = null
const data = ref({ totalEvents: 0, issueCount: 0, alertCount: 0, activeBaselines: 0, buildingBaselines: 0, alerts: [], topIssues: [], coverageStart: null, coverageEnd: null, projectionStatus: '', projectedAt: null })
const coverageText = ref('')

async function load() {
  if (!store.workspaceId) { loading.value = false; return }
  let r = await fetch('/api/v1/workspaces/' + store.workspaceId + '/dashboard')
  let d = await r.json()
  loading.value = false
  if (!d.coverage?.totalEvents) { isEmpty.value = true; return }
  data.value = {
    totalEvents: d.coverage?.totalEvents || 0, issueCount: d.topIssues?.length || 0,
    alertCount: d.recentAlerts?.length || 0, activeBaselines: d.baselineStatus?.active || 0,
    buildingBaselines: d.baselineStatus?.building || 0, alerts: d.recentAlerts || [],
    topIssues: d.topIssues || [], coverageStart: d.coverage?.windowStart, coverageEnd: d.coverage?.windowEnd,
    projectionStatus: d.latestProjection?.status, projectedAt: d.latestProjection?.projectedAt
  }
  coverageText.value = d.coverage?.windowStart ? `数据覆盖 ${d.coverage.windowStart?.slice(0,10)} ~ ${d.coverage.windowEnd?.slice(0,10)} · ${d.coverage.totalEvents} 条工单` : ''

  await nextTick()
  if (barChart) barChart.destroy()
  if (d.topIssues?.length && barChartRef.value) {
    barChart = new Chart(barChartRef.value, {
      type: 'bar', data: { labels: d.topIssues.map(i => i.canonicalName), datasets: [{ data: d.topIssues.map(i => i.feedbackCount), backgroundColor: ['#1E40AF','#3B82F6','#60A5FA','#93C5FD','#BFDBFE'], borderRadius: 6, barThickness: 32 }] },
      options: { responsive: true, indexAxis: 'y', plugins: { legend: { display: false } }, scales: { x: { beginAtZero: true, grid: { color: '#E2E8F0' } }, y: { grid: { display: false } } } }
    })
  }
}

onMounted(load)
watch(() => store.workspaceId, load)
</script>