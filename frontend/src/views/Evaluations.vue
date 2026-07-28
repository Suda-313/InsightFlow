<template>
  <div class="p-6 max-w-7xl mx-auto">
    <div class="flex items-start justify-between gap-4 mb-6">
      <div>
        <h1 class="text-xl font-bold">评测与运行基线</h1>
        <p class="text-xs text-slate-500 mt-1">固定金标题目的规则评分；用于比较 Prompt、模型或检索策略变更前后的质量、耗时和 Token。</p>
      </div>
      <button class="btn-primary" :disabled="running || !store.workspaceId" @click="runGoldEvaluation">
        {{ running ? '评测运行中…' : '运行金标评测' }}
      </button>
    </div>

    <div v-if="requestError" class="mb-4 px-3 py-2 rounded-lg bg-red-50 text-sm text-red-700">{{ requestError }}</div>
    <div v-if="loading" class="text-sm text-slate-400 py-8">正在加载评测记录…</div>

    <template v-else>
      <div class="grid grid-cols-1 lg:grid-cols-2 gap-6 mb-6">
        <section class="card p-5">
          <h2 class="font-semibold text-sm mb-3">历史批次对比</h2>
          <p v-if="runs.length < 2" class="text-sm text-slate-400 py-3">至少运行两次金标评测后，才能比较 Prompt 变更的影响。</p>
          <template v-else>
            <div class="grid grid-cols-1 sm:grid-cols-2 gap-3 mb-3">
              <label class="text-xs text-slate-500">候选批次
                <select v-model="candidateRunId" class="mt-1 w-full rounded border border-slate-200 px-2 py-2 text-sm bg-white">
                  <option v-for="run in runs" :key="run.run_id" :value="run.run_id">{{ runLabel(run) }}</option>
                </select>
              </label>
              <label class="text-xs text-slate-500">基线批次
                <select v-model="baselineRunId" class="mt-1 w-full rounded border border-slate-200 px-2 py-2 text-sm bg-white">
                  <option v-for="run in runs" :key="run.run_id" :value="run.run_id">{{ runLabel(run) }}</option>
                </select>
              </label>
            </div>
            <button class="btn-primary" :disabled="comparing || !canCompare" @click="compareRuns">
              {{ comparing ? '比较中…' : '比较两次评测' }}
            </button>
          </template>
        </section>

        <section class="card p-5">
          <h2 class="font-semibold text-sm mb-3">Agent 性能基线</h2>
          <p v-if="!performance.metrics?.length" class="text-sm text-slate-400 py-3">暂无已完成的模型调用记录。</p>
          <div v-else class="space-y-2 max-h-48 overflow-auto">
            <div v-for="metric in performance.metrics" :key="metric.agentType + metric.promptVersion + metric.modelName" class="rounded-lg bg-slate-50 px-3 py-2 text-xs">
              <div class="font-medium text-slate-700">{{ metric.agentType }} · {{ metric.promptVersion }} · {{ metric.modelName }}</div>
              <div class="text-slate-500 mt-1">{{ metric.succeededSampleCount }} 条样本 · p50/p95 {{ display(metric.p50LatencyMs) }}/{{ display(metric.p95LatencyMs) }} ms · 输入 Token {{ display(metric.p50PromptTokens) }}/{{ display(metric.p95PromptTokens) }}</div>
            </div>
          </div>
        </section>
      </div>

      <section v-if="comparison" class="card p-5">
        <div class="flex items-center justify-between gap-3 mb-4">
          <div>
            <h2 class="font-semibold text-sm">比较结果</h2>
            <p class="text-xs text-slate-500 mt-1">{{ comparison.candidate_prompt_version }} 相对 {{ comparison.baseline_prompt_version }}</p>
          </div>
          <span class="px-2.5 py-1 rounded-full text-xs" :class="comparison.passed ? 'bg-emerald-100 text-emerald-700' : 'bg-red-100 text-red-700'">{{ comparison.passed ? '质量门禁通过' : '发现质量退化' }}</span>
        </div>
        <p v-if="comparison.violations?.length" class="text-xs text-red-600 mb-4">{{ comparison.violations.join('、') }}</p>
        <div class="grid grid-cols-2 lg:grid-cols-4 gap-3 mb-5 text-sm">
          <MetricCard label="事实覆盖率" :candidate="percentage(comparison.candidate_metrics?.factCoverageRate)" :baseline="percentage(comparison.baseline_metrics?.factCoverageRate)" />
          <MetricCard label="证据引用率" :candidate="percentage(comparison.candidate_metrics?.evidenceCitationRate)" :baseline="percentage(comparison.baseline_metrics?.evidenceCitationRate)" />
          <MetricCard label="禁止断言命中率" :candidate="percentage(comparison.candidate_metrics?.forbiddenClaimHitRate)" :baseline="percentage(comparison.baseline_metrics?.forbiddenClaimHitRate)" />
          <MetricCard label="p95 耗时" :candidate="withUnit(comparison.candidate_metrics?.p95LatencyMs, ' ms')" :baseline="withUnit(comparison.baseline_metrics?.p95LatencyMs, ' ms')" />
          <MetricCard label="总 Token" :candidate="display(comparison.candidate_metrics?.totalTokens)" :baseline="display(comparison.baseline_metrics?.totalTokens)" />
        </div>
        <div class="overflow-auto">
          <table class="w-full text-sm text-left">
            <thead class="text-xs text-slate-500 border-b"><tr><th class="pb-2">题目</th><th class="pb-2">分类</th><th class="pb-2">变化</th><th class="pb-2">必要事实</th><th class="pb-2">禁止断言</th></tr></thead>
            <tbody>
              <tr v-for="delta in comparison.case_deltas" :key="delta.caseId" class="border-b border-slate-100">
                <td class="py-2 font-mono text-xs">{{ delta.caseId }}</td><td class="py-2">{{ delta.category }}</td>
                <td class="py-2"><span :class="deltaClass(delta.status)">{{ statusText(delta.status) }}</span></td>
                <td class="py-2">{{ signed(delta.coveredRequiredFactDelta) }}</td><td class="py-2">{{ signed(delta.hitForbiddenClaimDelta) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="card p-5 mt-6">
        <div class="flex items-start justify-between gap-4 mb-4">
          <div>
            <h2 class="font-semibold text-sm">企业知识库 RAG 评测</h2>
            <p class="text-xs text-slate-500 mt-1">使用当前 Workspace 可见的已发布文档运行受控检索，统计召回、引用正确性和无依据回答率。</p>
          </div>
          <button class="btn-primary" :disabled="ragRunning || !store.workspaceId" @click="runRagEvaluation">
            {{ ragRunning ? 'RAG 评测运行中…' : '运行 RAG 评测' }}
          </button>
        </div>
        <p v-if="!ragRuns.length" class="text-sm text-slate-400 py-2">暂无 RAG 评测记录。请先发布知识文档；没有已发布文档时仅会运行无知识依据题。</p>
        <div v-else class="space-y-3">
          <div class="grid grid-cols-1 sm:grid-cols-3 gap-3">
            <MetricCard label="检索召回率" :candidate="percentage(latestRagRun?.metrics?.retrievalRecallRate)" baseline="-" />
            <MetricCard label="引用正确性" :candidate="percentage(latestRagRun?.metrics?.citationCorrectnessRate)" baseline="-" />
            <MetricCard label="无依据回答率" :candidate="percentage(latestRagRun?.metrics?.ungroundedAnswerRate)" baseline="-" />
          </div>
          <p v-if="!latestRagRun?.metrics" class="text-xs text-amber-700 bg-amber-50 rounded px-3 py-2">
            历史批次已加载，但响应中缺少指标字段。请重新编译并重启后端（<code class="font-mono">.\mvnw.cmd compile</code>），然后使用 Ctrl+F5 强制刷新页面。
          </p>
          <div class="max-h-36 overflow-auto space-y-1 text-xs text-slate-500">
            <button
              v-for="run in ragRuns"
              :key="run.run_id"
              type="button"
              class="flex w-full justify-between gap-3 rounded px-3 py-2 text-left"
              :class="run.run_id === latestRagRun?.run_id ? 'bg-slate-100 text-slate-700' : 'bg-slate-50 hover:bg-slate-100'"
              @click="selectedRagRunId = run.run_id"
            >
              <span>
                <span class="block">{{ run.prompt_version }} · {{ run.retrieval_version }}</span>
                <span v-if="run.metrics" class="mt-0.5 block text-[11px] text-slate-400">
                  召回 {{ percentage(run.metrics.retrievalRecallRate) }} · 引用 {{ percentage(run.metrics.citationCorrectnessRate) }} · 无依据 {{ percentage(run.metrics.ungroundedAnswerRate) }}
                </span>
              </span>
              <span class="shrink-0">{{ run.created_at?.slice(0, 16) || run.run_id.slice(0, 8) }}</span>
            </button>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useWorkspaceStore } from '../stores/workspace'

// 当前工作区来自统一 Store；评测、性能与历史结果均不能跨工作区读取。
const store = useWorkspaceStore()
const runs = ref([])
const performance = ref({ metrics: [] })
const comparison = ref(null)
const ragRuns = ref([])
const selectedRagRunId = ref('')
const candidateRunId = ref('')
const baselineRunId = ref('')
const loading = ref(false)
const running = ref(false)
const comparing = ref(false)
const ragRunning = ref(false)
const requestError = ref('')
const canCompare = computed(() => candidateRunId.value && baselineRunId.value && candidateRunId.value !== baselineRunId.value)
// 历史列表按 created_at 倒序返回；默认展示最新一批次的脱敏指标。
const latestRagRun = computed(() => {
  if (!ragRuns.value.length) return null
  const selected = ragRuns.value.find(run => run.run_id === selectedRagRunId.value)
  return selected || ragRuns.value[0]
})

// 公共请求包装器将非 2xx 响应显式转换为页面错误，避免把 API 失败误显示成“暂无数据”。
async function request(path, options) {
  const response = await fetch(path, options)
  if (!response.ok) throw new Error(`请求失败（${response.status}）`)
  return response.json()
}

// 同时刷新历史批次和 Agent 聚合基线；两类请求共享 workspaceId，但互不依赖返回内容。
async function loadPage() {
  if (!store.workspaceId) return
  loading.value = true
  requestError.value = ''
  try {
    const base = '/api/v1/workspaces/' + store.workspaceId
    const [history, baseline, ragHistory] = await Promise.all([
      request(base + '/evaluations/gold'),
      request(base + '/agent-runs/metrics'),
      request(base + '/evaluations/rag')
    ])
    runs.value = Array.isArray(history) ? history : []
    performance.value = baseline || { metrics: [] }
    ragRuns.value = Array.isArray(ragHistory) ? ragHistory : []
    if (!ragRuns.value.some(run => run.run_id === selectedRagRunId.value)) {
      selectedRagRunId.value = ragRuns.value[0]?.run_id || ''
    }
    selectDefaultRuns()
  } catch (error) {
    requestError.value = error.message
    runs.value = []
    performance.value = { metrics: [] }
    ragRuns.value = []
  } finally {
    loading.value = false
  }
}

// 新批次默认作为候选，次新批次作为基线；保留用户已选择的有效批次，避免刷新后选择丢失。
function selectDefaultRuns() {
  if (!runs.value.some(run => run.run_id === candidateRunId.value)) candidateRunId.value = runs.value[0]?.run_id || ''
  if (!runs.value.some(run => run.run_id === baselineRunId.value) || baselineRunId.value === candidateRunId.value) {
    baselineRunId.value = runs.value.find(run => run.run_id !== candidateRunId.value)?.run_id || ''
  }
}

// 运行端点会实际消耗模型调用；按钮在请求期间禁用，成功后重新读取持久化历史而不是依赖临时结果。
async function runGoldEvaluation() {
  if (!store.workspaceId || running.value) return
  running.value = true
  requestError.value = ''
  try {
    await request('/api/v1/workspaces/' + store.workspaceId + '/evaluations/gold', { method: 'POST' })
    await loadPage()
  } catch (error) {
    requestError.value = error.message
  } finally {
    running.value = false
  }
}

// RAG 评测固定复用当前 Workspace 的受控检索；完成后刷新历史，而不是缓存模型回答正文。
async function runRagEvaluation() {
  if (!store.workspaceId || ragRunning.value) return
  ragRunning.value = true
  requestError.value = ''
  try {
    const response = await request('/api/v1/workspaces/' + store.workspaceId + '/evaluations/rag', { method: 'POST' })
    const task = await waitForRagTask(response.task_id)
    if (task.status !== 'succeeded') throw new Error(task.error_code || 'RAG 评测未完成')
    await loadPage()
  } catch (error) {
    requestError.value = error.message
  } finally {
    ragRunning.value = false
  }
}

// 后端仅在任务终态写入评测历史；轮询期间不保留模型回答或文档正文到浏览器内存。
async function waitForRagTask(taskId) {
  for (let attempt = 0; attempt < 180; attempt++) {
    const task = await request('/api/v1/workspaces/' + store.workspaceId + '/evaluations/rag/tasks/' + taskId)
    if (task.status === 'succeeded' || task.status === 'failed' || task.status === 'partial_failed') return task
    await new Promise(resolve => setTimeout(resolve, 2000))
  }
  throw new Error('RAG 评测等待超时，请稍后刷新查看任务状态')
}

// 对比只允许两个不同的公开批次 UUID；后端仍会校验它们属于相同工作区与数据集版本。
async function compareRuns() {
  if (!store.workspaceId || !canCompare.value || comparing.value) return
  comparing.value = true
  requestError.value = ''
  try {
    const base = '/api/v1/workspaces/' + store.workspaceId + '/evaluations/gold/'
    comparison.value = await request(base + candidateRunId.value + '/compare/' + baselineRunId.value)
  } catch (error) {
    comparison.value = null
    requestError.value = error.message
  } finally {
    comparing.value = false
  }
}

function runLabel(run) { return `${run.prompt_version} · ${run.created_at?.slice(0, 16) || run.run_id.slice(0, 8)}` }
function display(value) { return value ?? '-' }
function withUnit(value, unit) { return value == null ? '-' : value + unit }
function percentage(value) { return value == null ? '-' : (value * 100).toFixed(1) + '%' }
function signed(value) { return value == null ? '-' : value > 0 ? '+' + value : value }
function statusText(status) { return ({ improved: '提升', regressed: '退化', mixed: '有增有减', unchanged: '不变', missing: '缺失' })[status] || status }
function deltaClass(status) { return status === 'improved' ? 'text-emerald-600' : status === 'regressed' ? 'text-red-600' : 'text-slate-500' }

onMounted(loadPage)
watch(() => store.workspaceId, loadPage)
</script>

<script>
// 局部展示组件只传递已格式化指标，保持页面模板不重复处理候选/基线字段。
export default {
  components: {
    MetricCard: {
      props: ['label', 'candidate', 'baseline'],
      template: '<div class="rounded-lg bg-slate-50 p-3"><div class="text-xs text-slate-500">{{ label }}</div><div class="font-semibold mt-1">{{ candidate }}</div><div class="text-xs text-slate-400 mt-1">基线 {{ baseline }}</div></div>'
    }
  }
}
</script>
