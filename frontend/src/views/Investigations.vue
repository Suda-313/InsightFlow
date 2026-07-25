<template>
  <div class="p-6 max-w-7xl mx-auto">
    <header class="flex items-start justify-between gap-4 mb-6">
      <div><h1 class="text-2xl font-bold">调查中心</h1><p class="text-sm text-slate-500 mt-1">集中复核告警证据、人工处置和纠错候选；系统不会自动执行提案。</p></div>
      <router-link to="/reports" class="text-sm text-primary hover:underline">查看证据化报告 →</router-link>
    </header>

    <div v-if="loading" class="grid grid-cols-3 gap-4"><div v-for="index in 3" :key="index" class="card h-28 animate-pulse bg-slate-100"></div></div>
    <div v-else-if="error" class="card p-5 border-red-200 bg-red-50 text-red-700"><p>{{ error }}</p><button class="mt-3 text-sm underline" @click="load">重试</button></div>
    <div v-else-if="!cases.length" class="card py-16 text-center"><SearchCheck class="mx-auto w-11 h-11 text-slate-300" /><h2 class="mt-4 font-semibold">暂无待办调查</h2><p class="mt-2 text-sm text-slate-500">新告警会在后台冻结证据后出现在这里。</p></div>

    <div v-else class="grid grid-cols-1 lg:grid-cols-3 gap-5">
      <section class="space-y-3">
        <button v-for="item in cases" :key="item.id" @click="selectCase(item.id)" class="w-full text-left card p-4 transition" :class="selectedId === item.id ? 'border-primary ring-1 ring-primary/20' : 'hover:border-slate-300'">
          <div class="flex justify-between gap-3"><span class="font-semibold text-sm">调查 {{ item.id.slice(0, 8) }}</span><span class="status-pill" :class="statusClass(item.status)">{{ statusText(item.status) }}</span></div>
          <p class="mt-2 text-sm text-slate-600 line-clamp-2">{{ item.summary || item.errorMessage || '等待后台取证' }}</p>
          <time class="mt-2 block text-xs text-slate-400">{{ formatTime(item.updatedAt) }}</time>
        </button>
      </section>

      <section v-if="detail" class="lg:col-span-2 space-y-5">
        <article class="card p-5"><div class="flex items-center justify-between"><h2 class="font-semibold">证据快照</h2><span class="status-pill" :class="statusClass(detail.investigation.status)">{{ statusText(detail.investigation.status) }}</span></div>
          <p class="mt-2 text-sm text-slate-600">{{ detail.investigation.summary || '调查尚未生成摘要。' }}</p>
          <div class="mt-4 space-y-3"><div v-for="evidence in detail.evidence" :key="evidence.id" class="rounded-lg border border-slate-100 bg-slate-50 p-3"><div class="flex gap-2 items-center"><span class="font-medium text-sm">{{ evidence.title }}</span><span v-if="!evidence.sufficient" class="text-xs text-amber-700">数据不足</span></div><p class="mt-1 text-sm text-slate-600 whitespace-pre-wrap">{{ evidence.content }}</p></div></div>
        </article>

        <article class="card p-5"><h2 class="font-semibold">待人工确认的提案</h2><p class="mt-1 text-xs text-slate-500">先预览影响，再确认执行；无权限时服务端会明确拒绝。</p>
          <div v-if="!detail.proposals.length" class="py-6 text-sm text-slate-400">当前调查还没有可执行提案。</div>
          <div v-for="proposal in detail.proposals" :key="proposal.id" class="mt-4 rounded-lg border border-slate-200 p-4"><div class="flex justify-between gap-3"><div><h3 class="font-medium text-sm">{{ proposal.title }}</h3><p class="mt-1 text-sm text-slate-600">{{ proposal.rationale }}</p></div><span class="status-pill" :class="proposal.status === 'pending' ? 'bg-amber-100 text-amber-700' : 'bg-slate-100 text-slate-600'">{{ proposal.status === 'pending' ? '待确认' : '已执行' }}</span></div>
            <div class="mt-3 flex gap-2"><button class="btn-secondary text-xs" :disabled="proposalRunning" @click="previewProposal(proposal)">预览影响</button><button v-if="proposal.status === 'pending'" class="btn-primary text-xs" :disabled="proposalRunning || activePreview?.proposalId !== proposal.id" @click="executeProposal(proposal)">确认执行</button></div>
          </div>
          <pre v-if="activePreview" class="mt-4 overflow-auto rounded-lg bg-slate-900 p-3 text-xs text-slate-100">{{ activePreview.previewJson }}</pre>
        </article>

        <article v-if="executions.length" class="card p-5"><h2 class="font-semibold">已执行操作</h2><div v-for="execution in executions" :key="execution.id" class="mt-3 flex items-center justify-between gap-3 text-sm"><span>{{ execution.action }} · {{ execution.summary }}</span><button v-if="execution.status === 'executed'" class="text-primary hover:underline" :disabled="proposalRunning" @click="undoExecution(execution)">撤销</button><span v-else class="text-slate-400">已撤销</span></div></article>

        <article class="card p-5"><h2 class="font-semibold">提交纠错候选</h2><p class="mt-1 text-xs text-slate-500">候选不会直接改写规则，Owner 需在评测页面取得双评测基线后再发布。</p><div class="mt-3 flex flex-col sm:flex-row gap-2"><select v-model="correctionKind" class="rounded-lg border border-slate-300 px-3 py-2 text-sm"><option value="ISSUE_ALIAS">主题别名</option><option value="RULE_CANDIDATE">规则候选</option><option value="EVALUATION_CASE">评测样例</option></select><input v-model.trim="correctionContent" maxlength="2000" class="flex-1 rounded-lg border border-slate-300 px-3 py-2 text-sm" placeholder="写清问题、建议与可验证依据" /><button class="btn-secondary text-sm" :disabled="!correctionContent || correctionSubmitting" @click="submitCorrection">提交</button></div><p v-if="correctionMessage" class="mt-2 text-xs text-slate-500">{{ correctionMessage }}</p></article>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { SearchCheck } from 'lucide-vue-next'
import { useWorkspaceStore } from '../stores/workspace'

const store = useWorkspaceStore()
const cases = ref([]), detail = ref(null), selectedId = ref(''), loading = ref(true), error = ref('')
const activePreview = ref(null), proposalRunning = ref(false), executions = ref([])
const correctionKind = ref('ISSUE_ALIAS'), correctionContent = ref(''), correctionSubmitting = ref(false), correctionMessage = ref('')
const endpoint = () => `/api/v1/workspaces/${store.workspaceId}/investigations`

/** 加载待办卡片；401/403/失败均显示为可操作错误，而不是空数据。 */
async function load() {
  if (!store.workspaceId) { loading.value = false; return }
  loading.value = true; error.value = ''
  try { const response = await fetch(endpoint()); const data = await response.json(); if (!response.ok) throw new Error(data?.error?.message || '调查列表加载失败'); cases.value = data; if (!selectedId.value && data[0]) await selectCase(data[0].id) } catch (exception) { error.value = exception.message || '调查列表加载失败' } finally { loading.value = false }
}

/** 详情由单一接口返回卡片、证据与提案，避免前端拼接出不一致状态。 */
async function selectCase(caseId) {
  selectedId.value = caseId; activePreview.value = null; executions.value = []
  const response = await fetch(`${endpoint()}/${caseId}`); const data = await response.json()
  if (!response.ok) { error.value = data?.error?.message || '调查详情加载失败'; return }
  detail.value = data
  // 执行记录来自详情接口，刷新页面后仍可继续撤销，而不是仅保留本次浏览器内存状态。
  executions.value = data.executions || []
}

/** 预览不产生写操作，必须先完成才展示确认按钮。 */
async function previewProposal(proposal) {
  proposalRunning.value = true
  try { const response = await fetch(`${endpoint()}/${selectedId.value}/proposals/${proposal.id}/preview`, { method: 'POST' }); const data = await response.json(); if (!response.ok) throw new Error(data?.error?.message || '提案预览失败'); activePreview.value = data } catch (exception) { error.value = exception.message || '提案预览失败' } finally { proposalRunning.value = false }
}

/** 处置使用一次性幂等键；服务端负责角色、状态和审计，前端不自行修改卡片状态。 */
async function executeProposal(proposal) {
  proposalRunning.value = true
  try { const response = await fetch(`${endpoint()}/${selectedId.value}/proposals/${proposal.id}/execute`, { method: 'POST', headers: { 'Idempotency-Key': `web-${Date.now()}` } }); const data = await response.json(); if (!response.ok) throw new Error(data?.error?.message || '提案执行失败'); executions.value = [data, ...executions.value]; await load(); await selectCase(selectedId.value) } catch (exception) { error.value = exception.message || '提案执行失败' } finally { proposalRunning.value = false }
}

/** 撤销恢复待复核状态，仍保留原执行记录和审计历史。 */
async function undoExecution(execution) {
  proposalRunning.value = true
  try { const response = await fetch(`${endpoint()}/${selectedId.value}/executions/${execution.id}/undo`, { method: 'POST' }); const data = await response.json(); if (!response.ok) throw new Error(data?.error?.message || '撤销失败'); executions.value = executions.value.map(item => item.id === data.id ? data : item); await load(); await selectCase(selectedId.value) } catch (exception) { error.value = exception.message || '撤销失败' } finally { proposalRunning.value = false }
}

/** 纠错只提交候选；发布门禁故意留在评测链路，避免前端绕过双评测。 */
async function submitCorrection() {
  correctionSubmitting.value = true; correctionMessage.value = ''
  try { const response = await fetch(`${endpoint()}/${selectedId.value}/corrections`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ kind: correctionKind.value, content: correctionContent.value }) }); const data = await response.json(); if (!response.ok) throw new Error(data?.error?.message || '纠错候选提交失败'); correctionContent.value = ''; correctionMessage.value = `已提交候选 ${data.id.slice(0, 8)}，等待双评测门禁。` } catch (exception) { correctionMessage.value = exception.message || '纠错候选提交失败' } finally { correctionSubmitting.value = false }
}

function statusText(status) { return ({ queued: '排队中', investigating: '取证中', pending_review: '待复核', confirmed: '已确认', ignored: '已忽略', closed: '已关闭', failed: '失败' })[status] || status }
function statusClass(status) { return ({ pending_review: 'bg-amber-100 text-amber-700', confirmed: 'bg-emerald-100 text-emerald-700', investigating: 'bg-blue-100 text-blue-700', queued: 'bg-slate-100 text-slate-600', failed: 'bg-red-100 text-red-700', ignored: 'bg-slate-100 text-slate-600', closed: 'bg-slate-100 text-slate-600' })[status] || 'bg-slate-100 text-slate-600' }
function formatTime(value) { return value ? new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '-' }
onMounted(load)
watch(() => store.workspaceId, load)
</script>

<style scoped>
.status-pill { @apply rounded-full px-2 py-0.5 text-xs whitespace-nowrap; }
</style>
