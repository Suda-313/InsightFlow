<template>
  <div class="p-6">
    <div class="mb-6 flex items-start justify-between gap-4">
      <div class="flex-1">
        <h1 class="text-2xl font-bold">仪表盘</h1>
        <AnalysisDateRange
          v-if="!isEmpty && !loading && data.coverageStart"
          class="mt-3"
          :coverage-start="data.coverageStart"
          :coverage-end="data.coverageEnd"
          :from="analysisFrom"
          :to="analysisTo"
          @apply="onAnalysisApply"
        />
      </div>
      <div v-if="!isEmpty && !loading" class="text-right text-xs text-slate-500">
        <div>Topic Pack</div>
        <div class="font-medium text-slate-700 dark:text-slate-200">{{ packBinding.displayName || packBinding.packId }}</div>
        <div class="font-mono text-[10px]">{{ packBinding.packVersion }}</div>
      </div>
    </div>

    <div v-if="loadError" class="card p-8 text-center">
      <p class="text-red-600 mb-4">{{ loadError }}</p>
      <button class="btn-primary px-4 py-2 text-sm" @click="load">重试</button>
    </div>

    <div v-else-if="loading" class="space-y-6">
      <div class="grid grid-cols-4 gap-4"><div v-for="i in 4" :key="i" class="card p-5 animate-pulse"><div class="h-3 bg-slate-200 dark:bg-slate-700 rounded w-16 mb-3"></div><div class="h-8 bg-slate-200 dark:bg-slate-700 rounded w-20"></div></div></div>
      <div class="grid grid-cols-2 gap-6"><div class="card p-5 animate-pulse"><div class="h-4 bg-slate-200 dark:bg-slate-700 rounded w-24 mb-4"></div><div class="h-48 bg-slate-200 dark:bg-slate-700 rounded"></div></div><div class="card p-5 animate-pulse"><div class="h-4 bg-slate-200 dark:bg-slate-700 rounded w-24 mb-4"></div><div class="h-48 bg-slate-200 dark:bg-slate-700 rounded"></div></div></div>
    </div>

    <div v-else-if="isEmpty" class="text-center py-20">
      <div class="w-20 h-20 mx-auto mb-6 rounded-2xl bg-gradient-to-br from-primary to-primary-light flex items-center justify-center"><Database class="w-10 h-10 text-white" /></div>
      <h2 class="text-xl font-semibold mb-2">欢迎使用 InsightFlow</h2>
      <p class="text-slate-500 mb-8 max-w-md mx-auto">导入 CSV 客服工单数据，系统将自动进行 L2 表达分类与 L1 议题分析</p>
      <router-link to="/import" class="inline-flex items-center gap-2 px-6 py-3 bg-gradient-to-r from-primary to-primary-light text-white rounded-xl font-medium hover:shadow-lg transition"><Upload class="w-5 h-5" />开始导入数据</router-link>
    </div>

    <template v-else>
      <!-- KPI Cards -->
      <div class="grid grid-cols-4 gap-4 mb-6">
        <div class="relative overflow-hidden rounded-xl bg-gradient-to-br from-blue-50 to-blue-100 dark:from-blue-950 dark:to-blue-900 border border-blue-200 dark:border-blue-800 p-5">
          <div class="relative"><div class="text-xs font-medium text-blue-600 dark:text-blue-400 mb-1">总工单数</div><div class="text-3xl font-bold text-blue-900 dark:text-blue-100">{{ data.totalEvents }}</div><div class="text-xs text-blue-500/70 mt-1">累计导入</div></div>
        </div>
        <div class="relative overflow-hidden rounded-xl bg-gradient-to-br from-emerald-50 to-emerald-100 dark:from-emerald-950 dark:to-emerald-900 border border-emerald-200 dark:border-emerald-800 p-5">
          <div class="relative"><div class="text-xs font-medium text-emerald-600 dark:text-emerald-400 mb-1">L1 待复核</div><div class="text-3xl font-bold text-emerald-900 dark:text-emerald-100">{{ data.reviewPendingCount }}</div><div class="text-xs text-emerald-500/70 mt-1">歧义/多议题</div></div>
        </div>
        <div class="relative overflow-hidden rounded-xl bg-gradient-to-br from-amber-50 to-amber-100 dark:from-amber-950 dark:to-amber-900 border border-amber-200 dark:border-amber-800 p-5" :class="data.alertCount > 0 ? '!from-red-50 !to-red-100 !border-red-200 dark:!from-red-950 dark:!to-red-900 dark:!border-red-800' : ''">
          <div class="relative"><div class="text-xs font-medium mb-1" :class="data.alertCount > 0 ? 'text-red-600 dark:text-red-400' : 'text-amber-600 dark:text-amber-400'">活跃告警</div><div class="text-3xl font-bold" :class="data.alertCount > 0 ? 'text-red-900 dark:text-red-100' : 'text-amber-900 dark:text-amber-100'">{{ data.alertCount }}</div></div>
        </div>
        <div class="relative overflow-hidden rounded-xl bg-gradient-to-br from-violet-50 to-violet-100 dark:from-violet-950 dark:to-violet-900 border border-violet-200 dark:border-violet-800 p-5">
          <div class="relative"><div class="text-xs font-medium text-violet-600 dark:text-violet-400 mb-1">投影状态</div><div class="text-3xl font-bold text-violet-900 dark:text-violet-100">{{ data.projectionStatus === 'succeeded' ? '✓' : '—' }}</div><div class="text-xs text-violet-500/70 mt-1">{{ data.projectedAt ? data.projectedAt.slice(0,10) : '等待中' }}</div></div>
        </div>
      </div>

      <!-- L2 Primary View -->
      <div class="grid grid-cols-3 gap-6 mb-6">
        <div class="col-span-2 card p-5">
          <div class="flex items-center justify-between mb-4">
            <h3 class="font-semibold">L2 表达分布</h3>
            <span class="text-xs text-slate-400">点击类目钻取 L1 议题 · 趋势与分布均按分析范围</span>
          </div>
          <div class="space-y-3 mb-4">
            <button v-for="item in expressionDistribution" :key="item.key" type="button"
              class="w-full text-left group"
              @click="selectExpression(item.key, item.name)">
              <div class="flex items-center justify-between text-sm mb-1">
                <span class="font-medium" :class="selectedExpression === item.key ? 'text-primary' : ''">{{ item.name }}</span>
                <span class="text-slate-500">{{ item.feedbackCount }} · {{ expressionPercent(item.feedbackCount) }}%</span>
              </div>
              <div class="h-2.5 bg-slate-100 dark:bg-slate-700 rounded-full overflow-hidden">
                <div class="h-full rounded-full transition-all" :style="{ width: expressionPercent(item.feedbackCount) + '%', backgroundColor: expressionColor(item.key) }"></div>
              </div>
            </button>
          </div>
          <canvas ref="trendChartRef" height="160"></canvas>
        </div>

        <div class="card p-5 flex flex-col">
          <h3 class="font-semibold mb-3">L2 → L1 钻取</h3>
          <p v-if="!selectedExpression" class="text-sm text-slate-400 flex-1 flex items-center justify-center">← 选择左侧 L2 类目</p>
          <div v-else-if="drillLoading" class="flex-1 flex items-center justify-center text-sm text-slate-400">加载中…</div>
          <div v-else-if="drillError" class="text-sm text-red-600">{{ drillError }}</div>
          <div v-else-if="!drillTopics.length" class="text-sm text-slate-400 flex-1 flex items-center justify-center">该 L2 类目下暂无 L1 议题数据</div>
          <div v-else class="flex-1 overflow-auto space-y-2">
            <p class="text-xs text-slate-500 mb-2">{{ selectedExpressionName }} · Pack {{ drillPackId }}</p>
            <button v-for="topic in drillTopics" :key="topic.canonicalKey" type="button"
              class="w-full text-left p-3 rounded-lg border transition"
              :class="selectedTopic === topic.canonicalKey ? 'border-primary bg-primary/5' : 'border-slate-100 dark:border-slate-700 hover:border-primary/30'"
              @click="selectTopic(topic.canonicalKey, topic.canonicalName)">
              <div class="flex justify-between text-sm"><span>{{ topic.canonicalName }}</span><span class="font-bold">{{ topic.feedbackCount }}</span></div>
              <div class="text-[10px] font-mono text-slate-400 mt-0.5">{{ topic.canonicalKey }}</div>
            </button>
          </div>
          <div v-if="samples.length" class="mt-4 pt-4 border-t border-slate-100 dark:border-slate-700">
            <h4 class="text-xs font-semibold text-slate-500 mb-2">交叉样本（≤5）</h4>
            <div v-for="(sample, idx) in samples" :key="idx" class="text-xs text-slate-600 dark:text-slate-300 mb-2 p-2 bg-slate-50 dark:bg-slate-800 rounded">
              <div>{{ sample.text }}</div>
              <div v-if="sample.occurredAt || sample.sourceKind" class="text-[10px] text-slate-400 mt-1">
                {{ sample.occurredAt?.slice(0, 10) || '' }}{{ sample.sourceKind ? ' · ' + sample.sourceKind : '' }}
              </div>
            </div>
          </div>
          <p v-else-if="selectedTopic && samplesLoading" class="text-xs text-slate-400 mt-4">加载样本…</p>
        </div>
      </div>

      <!-- Alert Eligible Sub-panel (read-only supplementary view per wireframe §7.2) -->
      <div class="card mb-6 overflow-hidden">
        <button type="button" class="w-full flex items-center justify-between p-5 text-left hover:bg-slate-50 dark:hover:bg-slate-800/50 transition"
          @click="alertPanelExpanded = !alertPanelExpanded">
          <div>
            <h3 class="font-semibold flex items-center gap-2">
              <ShieldAlert class="w-4 h-4 text-amber-600" />
              可行动议题（alert_eligible）
            </h3>
            <p class="text-xs text-slate-500 mt-1">Pack 内标记为可告警的 L1 子集 · 只读概览，不修改告警状态</p>
          </div>
          <div class="flex items-center gap-4">
            <div v-if="!alertEligibleLoading" class="text-right">
              <div class="text-2xl font-bold text-amber-700 dark:text-amber-300">{{ alertEligible.totalFeedbackCount }}</div>
              <div class="text-[10px] text-slate-400">{{ alertEligible.eligibleTopicCount }} 个 eligible 议题</div>
            </div>
            <ChevronDown class="w-5 h-5 text-slate-400 transition-transform" :class="alertPanelExpanded ? 'rotate-180' : ''" />
          </div>
        </button>
        <div v-show="alertPanelExpanded" class="px-5 pb-5 border-t border-slate-100 dark:border-slate-700">
          <div v-if="alertEligibleLoading" class="py-8 text-center text-sm text-slate-400">加载可行动议题…</div>
          <div v-else-if="alertEligibleError" class="py-4 text-sm text-red-600">{{ alertEligibleError }}</div>
          <div v-else class="grid grid-cols-3 gap-6 pt-4">
            <div class="col-span-2">
              <div class="grid grid-cols-2 sm:grid-cols-3 gap-3 mb-4">
                <div v-for="topic in alertEligible.topics" :key="topic.canonicalKey"
                  class="p-3 rounded-lg border border-slate-100 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-800/30">
                  <div class="flex items-center justify-between mb-1">
                    <span class="text-sm font-medium truncate">{{ topic.canonicalName }}</span>
                    <span class="text-xs font-mono" :class="trendDirectionClass(topic.trendDirection)">{{ trendDirectionLabel(topic.trendDirection) }}</span>
                  </div>
                  <div class="text-xl font-bold">{{ topic.feedbackCount }}</div>
                  <div class="text-[10px] font-mono text-slate-400 truncate">{{ topic.canonicalKey }}</div>
                </div>
              </div>
              <canvas ref="alertTrendChartRef" height="120"></canvas>
            </div>
            <div>
              <h4 class="text-sm font-semibold mb-3">eligible 最近告警</h4>
              <div v-if="!alertEligible.recentAlerts.length" class="text-center py-8">
                <ShieldCheck class="w-8 h-8 mx-auto text-emerald-400 mb-2" />
                <p class="text-xs text-slate-400">eligible 议题暂无告警</p>
              </div>
              <div v-for="a in alertEligible.recentAlerts" :key="a.alertId" class="mb-2 p-3 bg-red-50 dark:bg-red-950/50 border border-red-100 dark:border-red-900/50 rounded-lg">
                <div class="flex items-center justify-between mb-1">
                  <span class="text-sm font-medium text-red-700">{{ a.issueName }}</span>
                  <span class="text-[10px] text-slate-400">{{ a.createdAt?.slice(5,16) }}</span>
                </div>
                <div class="text-xs">{{ a.currentCount }} 条异常反馈</div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Secondary: L1 Top + Alerts -->
      <div class="grid grid-cols-3 gap-6 mb-6">
        <div class="col-span-2 card p-5">
          <div class="flex items-center justify-between mb-4"><h3 class="font-semibold">L1 主题 Top 5</h3><router-link to="/data" class="text-xs text-primary hover:underline">查看全部</router-link></div>
          <canvas ref="barChartRef" height="200"></canvas>
        </div>
        <div class="card p-5">
          <h3 class="font-semibold mb-4">最近告警</h3>
          <div v-if="!data.alerts.length" class="text-center py-10"><ShieldCheck class="w-10 h-10 mx-auto text-emerald-400 mb-2" /><p class="text-sm text-slate-400">暂无告警</p></div>
          <div v-for="a in data.alerts" :key="a.alertId" class="mb-3 p-3 bg-red-50 dark:bg-red-950/50 border border-red-100 dark:border-red-900/50 rounded-lg">
            <div class="flex items-center justify-between mb-1"><router-link :to="'/data'" class="text-sm font-medium text-red-700 hover:underline">{{ a.issueName }}</router-link><span class="text-xs text-slate-400">{{ a.createdAt?.slice(5,16) }}</span></div>
            <div class="text-sm font-medium">{{ a.currentCount }} 条异常反馈</div>
          </div>
        </div>
      </div>

      <!-- Bottom Row -->
      <div class="grid grid-cols-3 gap-6">
        <div class="card p-5">
          <h3 class="font-semibold mb-4">Topic Pack 切换</h3>
          <p class="text-xs text-slate-500 mb-3">切换后需重新执行投影，新 L1 规则仅作用于后续投影；历史 link 保留原 canonical_key。</p>
          <select v-model="selectedPackId" class="w-full rounded border border-slate-200 dark:border-slate-600 px-2 py-2 text-sm mb-2" :disabled="packSaving">
            <option v-for="p in availablePacks" :key="p.packId" :value="p.packId">{{ p.displayName }} ({{ p.packId }})</option>
          </select>
          <button type="button" class="w-full rounded-lg bg-primary text-white text-sm py-2 disabled:opacity-50" :disabled="packSaving || !selectedPackId" @click="savePack">{{ packSaving ? '保存中…' : '保存 Pack 绑定' }}</button>
          <p v-if="packError" class="text-xs text-red-600 mt-2">{{ packError }}</p>
          <p v-if="packSaved" class="text-xs text-emerald-600 mt-2">已保存；请触发重投影以应用新规则。</p>
        </div>
        <div class="card p-5"><h3 class="font-semibold mb-4">快捷操作</h3>
          <div class="space-y-2">
            <router-link to="/import" class="flex items-center gap-3 p-3 rounded-lg bg-slate-50 dark:bg-slate-800 hover:bg-blue-50 dark:hover:bg-blue-950 transition text-sm"><Upload class="w-4 h-4 text-blue-600" />导入 CSV 数据</router-link>
            <router-link to="/data" class="flex items-center gap-3 p-3 rounded-lg bg-slate-50 dark:bg-slate-800 hover:bg-blue-50 dark:hover:bg-blue-950 transition text-sm"><Tags class="w-4 h-4 text-violet-600" />L1 议题与复核</router-link>
          </div>
        </div>
        <div class="card p-5"><h3 class="font-semibold mb-4">系统信息</h3>
          <div class="space-y-2 text-sm">
            <div class="flex justify-between"><span class="text-slate-500">数据覆盖</span><span class="font-mono text-xs">{{ data.coverageStart?.slice(0,10) || '-' }} ~ {{ data.coverageEnd?.slice(0,10) || '-' }}</span></div>
            <div class="flex justify-between"><span class="text-slate-500">Pack</span><span class="text-xs">{{ packBinding.packId }}</span></div>
            <div class="flex justify-between"><span class="text-slate-500">绑定方式</span><span class="text-xs">{{ packBinding.explicitlyBound ? 'Workspace 显式绑定' : '全局默认' }}</span></div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import { Upload, Database, ShieldCheck, ShieldAlert, Tags, ChevronDown } from 'lucide-vue-next'
import { Chart, BarController, LineController, CategoryScale, LinearScale, BarElement, LineElement, PointElement, Tooltip, Legend } from 'chart.js'
import { useWorkspaceStore } from '../stores/workspace'
import AnalysisDateRange from '../components/AnalysisDateRange.vue'
import { analysisQuery, defaultWindowFromCoverage, toDateInput } from '../lib/analysis-window'
Chart.register(BarController, LineController, CategoryScale, LinearScale, BarElement, LineElement, PointElement, Tooltip, Legend)

const store = useWorkspaceStore()
const loading = ref(true), isEmpty = ref(false), loadError = ref('')
const barChartRef = ref(null), trendChartRef = ref(null), alertTrendChartRef = ref(null)
let barChart = null, trendChart = null, alertTrendChart = null

const data = ref({ totalEvents: 0, alertCount: 0, reviewPendingCount: 0, alerts: [], topIssues: [], coverageStart: null, coverageEnd: null, projectionStatus: '', projectedAt: null })
const expressionDistribution = ref([])
const expressionTrend = ref([])
const analysisFrom = ref('')
const analysisTo = ref('')

const selectedExpression = ref(''), selectedExpressionName = ref('')
const drillLoading = ref(false), drillError = ref(''), drillTopics = ref([]), drillPackId = ref('')
const selectedTopic = ref(''), samples = ref([]), samplesLoading = ref(false)

const availablePacks = ref([]), selectedPackId = ref(''), packBinding = ref({ packId: '', packVersion: '', displayName: '', explicitlyBound: false })
const packSaving = ref(false), packError = ref(''), packSaved = ref(false)

const alertPanelExpanded = ref(true)
const alertEligibleLoading = ref(false), alertEligibleError = ref('')
const alertEligible = ref({ totalFeedbackCount: 0, eligibleTopicCount: 0, topics: [], trend: [], recentAlerts: [] })

const expressionColors = {
  expr_suggestion: '#3B82F6', expr_complaint: '#EF4444', expr_praise: '#10B981',
  expr_neutral: '#64748B', expr_other: '#CBD5E1'
}
const expressionColor = (key) => expressionColors[key] || '#94A3B8'
const expressionTotal = computed(() => expressionDistribution.value.reduce((s, i) => s + (i.feedbackCount || 0), 0))
const expressionPercent = (count) => expressionTotal.value ? Math.round((count / expressionTotal.value) * 100) : 0

const trendDirectionLabel = (dir) => ({ up: '↑', down: '↓', flat: '→' }[dir] || '→')
const trendDirectionClass = (dir) => ({
  up: 'text-red-600 dark:text-red-400',
  down: 'text-emerald-600 dark:text-emerald-400',
  flat: 'text-slate-400'
}[dir] || 'text-slate-400')

function windowQuery() {
  return analysisQuery(analysisFrom.value, analysisTo.value)
}

function syncAnalysisWindow(startIso, endIso) {
  analysisFrom.value = toDateInput(startIso)
  analysisTo.value = toDateInput(endIso)
}

async function onAnalysisApply({ from, to }) {
  analysisFrom.value = from
  analysisTo.value = to
  await load()
}

async function loadPacks() {
  if (!store.workspaceId) return
  try {
    const [listRes, bindRes] = await Promise.all([
      fetch('/api/v1/topic-packs'),
      fetch('/api/v1/workspaces/' + store.workspaceId + '/topic-pack')
    ])
    if (listRes.ok) availablePacks.value = await listRes.json()
    if (bindRes.ok) {
      packBinding.value = await bindRes.json()
      selectedPackId.value = packBinding.value.packId
    }
  } catch (_) { /* Pack 加载失败不阻断看板 */ }
}

async function loadAlertEligible() {
  if (!store.workspaceId) return
  alertEligibleLoading.value = true
  alertEligibleError.value = ''
  try {
    const r = await fetch('/api/v1/workspaces/' + store.workspaceId + '/dashboard/alert-eligible' + windowQuery())
    if (!r.ok) throw new Error('可行动议题加载失败 (' + r.status + ')')
    const d = await r.json()
    alertEligible.value = {
      totalFeedbackCount: d.totalFeedbackCount || 0,
      eligibleTopicCount: d.eligibleTopicCount || 0,
      topics: d.topics || [],
      trend: d.trend || [],
      recentAlerts: d.recentAlerts || []
    }
    await nextTick()
    renderAlertTrendChart()
  } catch (e) {
    alertEligibleError.value = e.message || '加载失败'
  } finally {
    alertEligibleLoading.value = false
  }
}

async function load() {
  if (!store.workspaceId) { loading.value = false; return }
  loading.value = true; loadError.value = ''; isEmpty.value = false
  selectedExpression.value = ''; drillTopics.value = []; samples.value = []
  const requestedFrom = analysisFrom.value
  const requestedTo = analysisTo.value
  try {
    const r = await fetch('/api/v1/workspaces/' + store.workspaceId + '/dashboard' + windowQuery())
    if (!r.ok) throw new Error('看板加载失败 (' + r.status + ')')
    const d = await r.json()
    loading.value = false
    if (!d.coverage?.totalEvents) { isEmpty.value = true; return }

    const summary = d.expressionSummary || {}
    expressionDistribution.value = summary.distribution || []
    expressionTrend.value = summary.trend || []
    data.value = {
      totalEvents: d.coverage?.totalEvents || 0,
      alertCount: d.recentAlerts?.length || 0,
      reviewPendingCount: summary.reviewPendingCount || 0,
      alerts: d.recentAlerts || [],
      topIssues: d.topIssues || [],
      coverageStart: d.coverage?.windowStart,
      coverageEnd: d.coverage?.windowEnd,
      projectionStatus: d.latestProjection?.status,
      projectedAt: d.latestProjection?.projectedAt
    }
    if (requestedFrom && requestedTo) {
      analysisFrom.value = requestedFrom
      analysisTo.value = requestedTo
    } else if (d.analysisWindow) {
      syncAnalysisWindow(d.analysisWindow.start, d.analysisWindow.end)
    } else {
      const defaults = defaultWindowFromCoverage(d.coverage?.windowStart, d.coverage?.windowEnd)
      analysisFrom.value = defaults.from
      analysisTo.value = defaults.to
    }
    if (summary.topicPackId) {
      packBinding.value = { ...packBinding.value, packId: summary.topicPackId, packVersion: summary.topicPackVersion }
    }
    await nextTick()
    renderCharts(d.topIssues || [])
    await Promise.all([loadPacks(), loadAlertEligible()])
  } catch (e) {
    loading.value = false
    loadError.value = e.message || '加载失败'
  }
}

function renderCharts(topIssues) {
  if (barChart) barChart.destroy()
  if (trendChart) trendChart.destroy()
  if (topIssues.length && barChartRef.value) {
    barChart = new Chart(barChartRef.value, {
      type: 'bar',
      data: { labels: topIssues.map(i => i.canonicalName), datasets: [{ data: topIssues.map(i => i.feedbackCount), backgroundColor: '#93C5FD', borderRadius: 6, barThickness: 28 }] },
      options: { responsive: true, indexAxis: 'y', plugins: { legend: { display: false } }, scales: { x: { beginAtZero: true } } }
    })
  }
  if (expressionTrend.value.length && trendChartRef.value) {
    const keys = expressionDistribution.value.map(i => i.key)
    const labels = expressionTrend.value.map(p => p.bucketStart?.slice(5, 10) || '')
    const datasets = keys.map(key => ({
      label: expressionDistribution.value.find(i => i.key === key)?.name || key,
      data: expressionTrend.value.map(p => p.countsByExpression?.[key] || 0),
      borderColor: expressionColor(key),
      backgroundColor: expressionColor(key) + '33',
      tension: 0.3,
      fill: false
    }))
    trendChart = new Chart(trendChartRef.value, {
      type: 'line',
      data: { labels, datasets },
      options: { responsive: true, plugins: { legend: { position: 'bottom', labels: { boxWidth: 10, font: { size: 10 } } } }, scales: { y: { beginAtZero: true } } }
    })
  }
}

function renderAlertTrendChart() {
  if (alertTrendChart) alertTrendChart.destroy()
  const trend = alertEligible.value.trend || []
  if (!trend.length || !alertTrendChartRef.value) return
  alertTrendChart = new Chart(alertTrendChartRef.value, {
    type: 'line',
    data: {
      labels: trend.map(p => p.bucketStart?.slice(5, 10) || ''),
      datasets: [{
        label: 'eligible 反馈量',
        data: trend.map(p => p.feedbackCount || 0),
        borderColor: '#F59E0B',
        backgroundColor: '#F59E0B33',
        tension: 0.3,
        fill: true
      }]
    },
    options: {
      responsive: true,
      plugins: { legend: { display: false } },
      scales: { y: { beginAtZero: true } }
    }
  })
}

async function selectExpression(key, name) {
  selectedExpression.value = key
  selectedExpressionName.value = name
  selectedTopic.value = ''
  samples.value = []
  drillLoading.value = true
  drillError.value = ''
  try {
    const r = await fetch('/api/v1/workspaces/' + store.workspaceId + '/expressions/' + key + '/topics' + windowQuery())
    if (!r.ok) throw new Error('钻取失败 (' + r.status + ')')
    const d = await r.json()
    drillTopics.value = d.topics || []
    drillPackId.value = d.topicPackId || ''
  } catch (e) {
    drillError.value = e.message || '钻取失败'
    drillTopics.value = []
  } finally {
    drillLoading.value = false
  }
}

async function selectTopic(topicKey, topicName) {
  selectedTopic.value = topicKey
  samples.value = []
  samplesLoading.value = true
  try {
    const r = await fetch('/api/v1/workspaces/' + store.workspaceId + '/expressions/' + selectedExpression.value + '/topics/' + topicKey + '/samples' + windowQuery())
    if (r.ok) samples.value = await r.json()
  } finally {
    samplesLoading.value = false
  }
}

async function savePack() {
  packSaving.value = true; packError.value = ''; packSaved.value = false
  try {
    const r = await fetch('/api/v1/workspaces/' + store.workspaceId + '/topic-pack', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ packId: selectedPackId.value })
    })
    if (!r.ok) {
      const err = await r.json().catch(() => ({}))
      throw new Error(err.message || '保存失败 (' + r.status + ')')
    }
    packBinding.value = await r.json()
    packSaved.value = true
  } catch (e) {
    packError.value = e.message || '保存失败'
  } finally {
    packSaving.value = false
  }
}

onMounted(load)
watch(() => store.workspaceId, load)
</script>
