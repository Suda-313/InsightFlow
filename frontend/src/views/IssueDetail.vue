<template>
  <div class="p-6">
    <router-link to="/issues" class="text-sm text-primary hover:underline mb-4 inline-flex items-center gap-1"><ArrowLeft class="w-4 h-4" />返回主题列表</router-link>
    <div v-if="loading" class="flex items-center justify-center py-20"><div class="w-8 h-8 border-2 border-primary border-t-transparent rounded-full animate-spin"></div></div>
    <div v-else-if="error" class="card p-8 text-center mt-4"><AlertTriangle class="w-10 h-10 mx-auto text-red-400 mb-2" /><p class="text-red-500">{{ error }}</p></div>
    <div v-else class="mt-4">
      <div class="flex items-center gap-4 mb-6">
        <div class="w-12 h-12 rounded-xl bg-gradient-to-br from-blue-100 to-blue-200 dark:from-blue-900 dark:to-blue-800 flex items-center justify-center"><Tags class="w-6 h-6 text-blue-600" /></div>
        <div><h1 class="text-2xl font-bold">{{ data.canonicalName }}</h1><p class="text-sm text-slate-500 font-mono">{{ data.canonicalKey }}</p></div>
        <span class="ml-auto px-3 py-1 text-sm rounded-full" :class="data.status === 'active' ? 'bg-emerald-100 text-emerald-700' : 'bg-amber-100 text-amber-700'">{{ data.status || 'active' }}</span>
      </div>

      <div class="grid grid-cols-3 gap-4 mb-6">
        <div class="card p-4"><div class="text-xs text-slate-500 mb-1">最近 7 天反馈</div><div class="text-2xl font-bold">{{ totalRecent }}</div></div>
        <div class="card p-4"><div class="text-xs text-slate-500 mb-1">告警次数</div><div class="text-2xl font-bold" :class="data.alerts?.length > 0 ? 'text-red-600' : ''">{{ data.alerts?.length || 0 }}</div></div>
        <div class="card p-4"><div class="text-xs text-slate-500 mb-1">EWMA 基线</div><div class="text-2xl font-bold">{{ baselineEwma }}</div></div>
      </div>

      <div class="grid grid-cols-2 gap-6 mb-6">
        <div class="card p-5">
          <h3 class="font-semibold mb-4">7 天趋势</h3>
          <canvas ref="trendChartRef" height="200"></canvas>
          <div v-if="!data.recentTrend?.length" class="text-center text-slate-400 py-10">暂无趋势数据</div>
        </div>
        <div class="card p-5">
          <h3 class="font-semibold mb-4">告警历史</h3>
          <div v-if="!data.alerts?.length" class="text-center py-10"><ShieldCheck class="w-8 h-8 mx-auto text-emerald-400 mb-2" /><p class="text-sm text-slate-400">暂无告警</p></div>
          <div v-for="a in data.alerts" :key="a.alertId" class="mb-2 p-3 bg-red-50 dark:bg-red-900/20 rounded-lg">
            <div class="flex items-center justify-between"><span class="text-sm font-mono">{{ a.alertId?.slice(0,8) }}</span><span class="text-xs text-slate-400">{{ a.createdAt?.slice(0,16) }}</span></div>
            <div class="text-sm font-bold text-red-600 mt-1">{{ a.currentCount }} 条异常反馈</div>
          </div>
        </div>
      </div>

      <div v-if="data.baseline" class="card p-5">
        <h3 class="font-semibold mb-4">基线信息</h3>
        <div class="grid grid-cols-3 gap-4 text-sm">
          <div><span class="text-slate-500">EWMA 值</span><div class="font-bold">{{ data.baseline.baselineEwma?.toFixed(1) || '-' }}</div></div>
          <div><span class="text-slate-500">标准差</span><div class="font-bold">{{ data.baseline.baselineStddev?.toFixed(1) || '-' }}</div></div>
          <div><span class="text-slate-500">活跃天数</span><div class="font-bold">{{ data.baseline.activeBuckets || '-' }}</div></div>
          <div><span class="text-slate-500">分类</span><div class="font-bold">{{ data.baseline.classification || 'normal' }}</div></div>
          <div><span class="text-slate-500">状态</span><div class="font-bold">{{ data.baseline.status || '-' }}</div></div>
          <div><span class="text-slate-500">最近值</span><div class="font-bold">{{ data.baseline.lastValue || '-' }}</div></div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowLeft, Tags, ShieldCheck, AlertTriangle } from 'lucide-vue-next'
import { Chart, LineController, CategoryScale, LinearScale, PointElement, LineElement, Tooltip, Filler } from 'chart.js'
import { useWorkspaceStore } from '../stores/workspace'
Chart.register(LineController, CategoryScale, LinearScale, PointElement, LineElement, Tooltip, Filler)

const store = useWorkspaceStore()
const route = useRoute()
const data = ref({}), loading = ref(true), error = ref('')
const trendChartRef = ref(null)
let trendChart = null

const totalRecent = computed(() => (data.value.recentTrend || []).reduce((s, p) => s + p.feedbackCount, 0))
const baselineEwma = computed(() => data.value.baseline?.baselineEwma?.toFixed(1) || '-')

async function load() {
  if (!store.workspaceId) { setTimeout(load, 200); return }
  loading.value = true; error.value = ''
  try {
    let r = await fetch('/api/v1/workspaces/' + store.workspaceId + '/issues/' + route.params.key)
    let d = await r.json()
    if (d.error) { error.value = d.error.message || '加载失败'; loading.value = false; return }
    data.value = d
    await nextTick()
    if (trendChart) trendChart.destroy()
    if (d.recentTrend?.length && trendChartRef.value) {
      trendChart = new Chart(trendChartRef.value, {
        type: 'line', data: { labels: d.recentTrend.map(p => p.bucketStart?.slice(0,10)), datasets: [{ data: d.recentTrend.map(p => p.feedbackCount), borderColor: '#3B82F6', backgroundColor: 'rgba(59,130,246,0.1)', fill: true, tension: 0.3, pointRadius: 3 }] },
        options: { responsive: true, plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true }, x: { grid: { display: false } } } }
      })
    }
  } catch (e) { error.value = '加载失败: ' + e.message }
  loading.value = false
}

onMounted(load)
watch(() => store.workspaceId, (v) => { if (v) load() })
</script>