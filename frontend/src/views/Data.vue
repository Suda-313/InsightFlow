<template>
  <div class="flex h-full">
    <!-- Topic List (Left) -->
    <div class="w-64 border-r border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 overflow-auto">
      <div class="p-4 border-b border-slate-200 dark:border-slate-700">
        <h2 class="font-semibold text-sm">主题列表</h2>
        <p class="text-xs text-slate-400 mt-1">{{ issues.length }} 个主题</p>
      </div>
      <div v-if="loading" class="p-4 space-y-2"><div v-for="i in 5" :key="i" class="h-10 bg-slate-100 dark:bg-slate-700 rounded animate-pulse"></div></div>
      <div v-for="i in issues" :key="i.canonicalKey" @click="selectIssue(i.canonicalKey)" class="px-4 py-3 cursor-pointer transition-all duration-150 hover:bg-primary/5 border-l-2" :class="selected === i.canonicalKey ? 'border-l-primary bg-primary/5 font-medium' : 'border-l-transparent'">
        <div class="text-sm">{{ i.canonicalName }}</div>
        <div class="flex items-center justify-between mt-1">
          <span class="text-xs text-slate-400 font-mono">{{ i.canonicalKey }}</span>
          <span class="text-xs font-bold">{{ i.feedbackCount }}</span>
        </div>
      </div>
    </div>

    <!-- Detail Panel (Right) -->
    <div class="flex-1 overflow-auto p-6">
      <section class="card p-5 mb-6">
        <div class="flex items-center justify-between mb-3">
          <div><h2 class="font-semibold">待人工复核</h2><p class="text-xs text-slate-500 mt-1">仅展示规则无法可靠收敛的多主题或混合情绪评论。</p></div>
          <span class="text-sm font-bold">{{ reviewCandidates.length }}</span>
        </div>
        <div class="flex gap-2 mb-3"><input v-model="newTopic" class="flex-1 rounded border border-slate-200 px-2 py-1 text-sm" placeholder="提交新的主题候选（不会直接生效）"><button class="text-primary hover:underline text-sm" @click="submitNewTopic">提交候选</button></div>
        <div v-if="!reviewCandidates.length" class="text-sm text-slate-400">暂无待复核候选</div>
        <div v-for="candidate in reviewCandidates" :key="candidate.id" class="border-t border-slate-100 py-3 text-sm">
          <p class="text-slate-700 dark:text-slate-200">{{ candidate.sampleText }}</p>
          <p class="text-xs text-slate-500 mt-1">原因：{{ candidate.reasonCode }} · 建议：{{ candidate.suggestedIssueKey || '无' }} / {{ candidate.suggestedSentiment || '无' }}</p>
          <div class="flex gap-3 mt-2"><button class="text-emerald-600 hover:underline" @click="resolveCandidate(candidate.id, 'confirm')">确认</button><button class="text-slate-500 hover:underline" @click="resolveCandidate(candidate.id, 'ignore')">忽略</button></div>
        </div>
      </section>
      <div v-if="!detail" class="card p-12 text-center mt-20">
        <BarChart3 class="w-12 h-12 mx-auto text-slate-300 mb-3" />
        <p class="text-slate-500">选择左侧主题查看详细数据</p>
      </div>

      <template v-else>
        <div class="flex items-center gap-4 mb-6">
          <div class="w-10 h-10 rounded-xl bg-gradient-to-br from-blue-100 to-blue-200 flex items-center justify-center"><Tags class="w-5 h-5 text-blue-600" /></div>
          <div><h1 class="text-xl font-bold">{{ detail.canonicalName }}</h1><p class="text-xs text-slate-500 font-mono">{{ detail.canonicalKey }}</p></div>
          <span class="ml-auto px-2.5 py-1 text-xs rounded-full" :class="detail.status === 'active' ? 'bg-emerald-100 text-emerald-700' : 'bg-amber-100 text-amber-700'">{{ detail.status || 'active' }}</span>
        </div>

        <div class="grid grid-cols-3 gap-4 mb-6">
          <div class="card p-4"><div class="text-xs text-slate-500 mb-1">最近 7 天</div><div class="text-2xl font-bold">{{ totalRecent }}</div></div>
          <div class="card p-4"><div class="text-xs text-slate-500 mb-1">告警次数</div><div class="text-2xl font-bold" :class="(detail.alerts || []).length > 0 ? 'text-red-600' : ''">{{ (detail.alerts || []).length }}</div></div>
          <div class="card p-4"><div class="text-xs text-slate-500 mb-1">EWMA 基线</div><div class="text-2xl font-bold">{{ baselineEwma }}</div></div>
        </div>

        <div class="grid grid-cols-2 gap-6 mb-6">
          <div class="card p-5"><h3 class="font-semibold text-sm mb-3">7 天趋势</h3><canvas ref="trendRef" height="200"></canvas></div>
          <div class="card p-5"><h3 class="font-semibold text-sm mb-3">告警历史</h3>
            <div v-if="!detail.alerts?.length" class="py-8 text-center text-sm text-slate-400">暂无告警</div>
            <div v-for="a in detail.alerts" :key="a.alertId" class="p-3 bg-red-50 dark:bg-red-900/20 rounded-lg mb-2"><div class="flex justify-between text-xs"><span class="font-mono">{{ a.alertId?.slice(0,8) }}</span><span>{{ a.createdAt?.slice(5,16) }}</span></div><div class="text-sm font-bold text-red-600 mt-1">{{ a.currentCount }} 条</div></div>
          </div>
        </div>

        <div class="grid grid-cols-2 gap-6 mb-6">
          <div class="card p-5" v-if="detail.baseline"><h3 class="font-semibold text-sm mb-3">基线信息</h3>
            <div class="grid grid-cols-3 gap-3 text-sm">
              <div><span class="text-slate-500">EWMA</span><div class="font-bold">{{ detail.baseline.baselineEwma?.toFixed(1) || '-' }}</div></div>
              <div><span class="text-slate-500">标准差</span><div class="font-bold">{{ detail.baseline.baselineStddev?.toFixed(1) || '-' }}</div></div>
              <div><span class="text-slate-500">活跃天数</span><div class="font-bold">{{ detail.baseline.activeBuckets || '-' }}</div></div>
              <div><span class="text-slate-500">分类</span><div class="font-bold">{{ detail.baseline.classification || 'normal' }}</div></div>
              <div><span class="text-slate-500">状态</span><div class="font-bold">{{ detail.baseline.status || '-' }}</div></div>
              <div><span class="text-slate-500">最近值</span><div class="font-bold">{{ detail.baseline.lastValue || '-' }}</div></div>
            </div>
          </div>
          <div class="card p-5"><h3 class="font-semibold text-sm mb-3">样本反馈</h3>
            <div v-if="!samples.length" class="text-sm text-slate-400 py-4">暂无样本</div>
            <div v-for="(s, idx) in samples" :key="idx" class="p-2.5 bg-slate-50 dark:bg-slate-800 rounded-lg mb-2 text-sm text-slate-600 dark:text-slate-300">{{ s }}</div>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import { BarChart3, Tags } from 'lucide-vue-next'
import { Chart, LineController, CategoryScale, LinearScale, PointElement, LineElement, Tooltip, Filler } from 'chart.js'
import { useWorkspaceStore } from '../stores/workspace'
Chart.register(LineController, CategoryScale, LinearScale, PointElement, LineElement, Tooltip, Filler)

const store = useWorkspaceStore()
const issues = ref([]), loading = ref(true), selected = ref(''), detail = ref(null), samples = ref([]), reviewCandidates = ref([]), newTopic = ref('')
const trendRef = ref(null)
let trendChart = null
const totalRecent = computed(() => (detail.value?.recentTrend || []).reduce((s, p) => s + p.feedbackCount, 0))
const baselineEwma = computed(() => detail.value?.baseline?.baselineEwma?.toFixed(1) || '-')

async function loadIssues() {
  if (!store.workspaceId) return; loading.value = true
  let r = await fetch('/api/v1/workspaces/' + store.workspaceId + '/issues')
  let d = await r.json()
  issues.value = Array.isArray(d) ? d : []
  const reviews = await fetch('/api/v1/workspaces/' + store.workspaceId + '/feedback-reviews')
  reviewCandidates.value = reviews.ok ? await reviews.json() : []
  loading.value = false
  if (issues.value.length && !selected.value) selectIssue(issues.value[0].canonicalKey)
}

async function resolveCandidate(id, action) {
  const response = await fetch('/api/v1/workspaces/' + store.workspaceId + '/feedback-reviews/' + id + '/' + action, { method: 'POST' })
  if (!response.ok) return
  reviewCandidates.value = reviewCandidates.value.filter(item => item.id !== id)
}

async function submitNewTopic() {
  const content = newTopic.value.trim()
  if (!content) return
  const response = await fetch('/api/v1/workspaces/' + store.workspaceId + '/feedback-reviews/new-topic', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ content }) })
  if (response.ok) newTopic.value = ''
}

async function selectIssue(key) {
  selected.value = key
  let r = await fetch('/api/v1/workspaces/' + store.workspaceId + '/issues/' + key)
  detail.value = await r.json()
  samples.value = (detail.value.sampleTexts || []).slice(0, 5)

  await nextTick()
  if (trendChart) trendChart.destroy()
  if (detail.value.recentTrend?.length && trendRef.value) {
    trendChart = new Chart(trendRef.value, { type: 'line', data: { labels: detail.value.recentTrend.map(p => p.bucketStart?.slice(0,10)), datasets: [{ data: detail.value.recentTrend.map(p => p.feedbackCount), borderColor: '#3B82F6', backgroundColor: 'rgba(59,130,246,0.1)', fill: true, tension: 0.3, pointRadius: 3 }] }, options: { responsive: true, plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true } } } })
  }
}

onMounted(loadIssues)
watch(() => store.workspaceId, loadIssues)
</script>
