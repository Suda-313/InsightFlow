<template>
  <div class="p-6">
    <h1 class="text-2xl font-bold mb-6">数据导入</h1>

    <!-- Step 1: Upload -->
    <div v-if="step === 1" class="card p-6">
      <div class="border-2 border-dashed border-slate-300 dark:border-slate-600 rounded-xl p-12 text-center cursor-pointer hover:border-primary transition" @click="$refs.fileInput.click()" @dragover.prevent @drop.prevent="onDrop">
        <UploadCloud class="w-12 h-12 mx-auto text-slate-400 mb-4" />
        <p class="text-slate-600 dark:text-slate-300 font-medium mb-1">点击或拖拽上传 CSV 文件</p>
        <p class="text-xs text-slate-400">支持 UTF-8 编码的 CSV 文件</p>
        <input ref="fileInput" type="file" accept=".csv" class="hidden" @change="onFileChange">
      </div>
      <div v-if="uploadMsg" class="mt-4 text-center" :class="uploadOk ? 'text-emerald-500' : 'text-red-500'">
        <div class="flex items-center justify-center gap-2"><CheckCircle v-if="uploadOk" class="w-4 h-4" /><AlertCircle v-else class="w-4 h-4" />{{ uploadMsg }}</div>
      </div>
    </div>

    <!-- Step 2: Mapping (only shown when auto-match fails) -->
    <div v-if="step === 2" class="card p-5">
      <h3 class="font-semibold mb-3">手动映射 — 部分列未自动识别</h3>
      <div class="space-y-3 max-w-md">
        <div v-for="f in fields" :key="f.key" class="flex items-center gap-2">
          <label class="text-xs w-24 shrink-0" :class="mapping[f.key] ? 'text-slate-500' : 'text-red-500'">{{ f.label }} {{ mapping[f.key] ? '✓' : '⚠' }}</label>
          <select v-model="mapping[f.key]" class="flex-1 bg-slate-100 dark:bg-slate-700 rounded-lg px-2 py-1.5 text-sm border-0 outline-none">
            <option value="">-- 选择列 --</option>
            <option v-for="h in headers" :key="h" :value="h">{{ h }}</option>
          </select>
        </div>
      </div>
      <button @click="submitMapping" :disabled="!allMapped" class="btn-primary mt-4">确认映射</button>
    </div>

    <!-- Step 3: Ready / processing / result -->
    <div v-if="step === 3" class="card p-6">
      <div class="flex items-center gap-3 mb-4">
        <div class="w-10 h-10 rounded-xl flex items-center justify-center"
          :class="statusIconClass">
          <Loader2 v-if="pipelineStatus === 'importing' || pipelineStatus === 'projecting'" class="w-5 h-5 animate-spin" />
          <CheckCircle v-else-if="pipelineStatus === 'completed'" class="w-5 h-5 text-emerald-600" />
          <AlertCircle v-else-if="pipelineStatus === 'failed'" class="w-5 h-5 text-red-600" />
          <CheckCircle v-else class="w-5 h-5 text-emerald-600" />
        </div>
        <div>
          <h3 class="font-semibold">{{ statusTitle }}</h3>
          <p class="text-xs text-slate-500">{{ statusSubtitle }}</p>
        </div>
      </div>

      <div v-if="originalFilename" class="text-xs text-slate-400 mb-3">文件：{{ originalFilename }}</div>

      <div class="grid grid-cols-2 gap-2 mb-4 text-sm">
        <div v-for="f in fields" :key="f.key" class="flex items-center justify-between p-2 bg-slate-50 dark:bg-slate-800 rounded-lg">
          <span class="text-slate-500">{{ f.label }}</span>
          <span class="font-mono text-xs">{{ mapping[f.key] }}</span>
        </div>
      </div>

      <div v-if="pipelineStatus === 'idle'" class="flex gap-2">
        <button @click="startImport" :disabled="importing" class="btn-accent px-6 py-2.5">{{ importing ? '提交中…' : '开始导入' }}</button>
        <button @click="step = 2" class="text-sm text-slate-500 hover:text-slate-700 px-3 py-2">修改映射</button>
      </div>

      <div v-if="resultSummary" class="mt-4 p-3 rounded-lg text-sm" :class="resultSummaryClass">
        {{ resultSummary }}
      </div>

      <div v-if="pipelineStatus === 'completed'" class="mt-4 flex gap-3">
        <router-link to="/dashboard" class="btn-primary text-sm px-4 py-2">查看仪表盘</router-link>
        <button class="text-sm text-slate-500 hover:text-slate-700 px-3 py-2" @click="resetForNewUpload">导入新文件</button>
      </div>
      <div v-else-if="pipelineStatus === 'failed'" class="mt-4 flex gap-3">
        <button class="btn-primary text-sm px-4 py-2" @click="refreshResult">刷新状态</button>
        <button class="text-sm text-slate-500 hover:text-slate-700 px-3 py-2" @click="resetForNewUpload">重新上传</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { UploadCloud, CheckCircle, AlertCircle, Loader2 } from 'lucide-vue-next'
import { useWorkspaceStore } from '../stores/workspace'

const store = useWorkspaceStore()

const step = ref(1), headers = ref([]), fileId = ref(''), originalFilename = ref('')
const uploadMsg = ref(''), uploadOk = ref(false), importing = ref(false)
const pipelineStatus = ref('idle') // idle | importing | projecting | completed | failed
const resultSummary = ref(''), resultSummaryClass = ref('')
const fields = [{ key: 'feedback_text', label: '反馈内容' }, { key: 'occurred_at', label: '发生时间' }, { key: 'source', label: '来源' }, { key: 'external_ref', label: '工单号' }]
const CANONICAL_IMPORT_KEYS = ['feedback_text', 'occurred_at', 'source', 'external_ref']
const mapping = ref({})
const allMapped = computed(() => Object.values(mapping.value).filter(Boolean).length === 4)

let pollTimer = null

const statusTitle = computed(() => ({
  idle: '已自动识别列映射',
  importing: '正在导入 CSV…',
  projecting: '导入完成，正在后台分析…',
  completed: '导入与分析已完成',
  failed: '处理失败',
}[pipelineStatus.value] || '已自动识别列映射'))

const statusSubtitle = computed(() => ({
  idle: '点击下方按钮开始导入',
  importing: '请勿关闭页面；切到其他页面后返回仍可看到进度',
  projecting: '正在进行主题分类与 L2 表达标注，请稍候',
  completed: '数据已写入看板，可前往仪表盘查看',
  failed: '请查看下方错误说明，或刷新后重试',
}[pipelineStatus.value] || ''))

const statusIconClass = computed(() => ({
  idle: 'bg-emerald-100 dark:bg-emerald-900',
  importing: 'bg-amber-100 dark:bg-amber-900',
  projecting: 'bg-violet-100 dark:bg-violet-900',
  completed: 'bg-emerald-100 dark:bg-emerald-900',
  failed: 'bg-red-100 dark:bg-red-900',
}[pipelineStatus.value] || 'bg-emerald-100 dark:bg-emerald-900'))

function baseUrl() { return '/api/v1/workspaces/' + store.workspaceId + '/imports/files' }

/** Canonical CSV v1 表头精确匹配（trim 后 case-sensitive），未命中时再走中文关键词兜底。 */
function autoMapImportHeaders(headerList) {
  const result = {}
  headerList.forEach(h => {
    const trimmed = h.trim()
    if (CANONICAL_IMPORT_KEYS.includes(trimmed)) result[trimmed] = h
  })
  headerList.forEach(h => {
    if (!result.feedback_text && h.includes('反馈')) result.feedback_text = h
    if (!result.occurred_at && h.includes('时间')) result.occurred_at = h
    if (!result.source && h.includes('来源')) result.source = h
    if (!result.external_ref && h.includes('工单')) result.external_ref = h
  })
  return result
}

function applyFileView(view) {
  fileId.value = view.id
  originalFilename.value = view.original_filename || ''
  headers.value = view.headers || []
  if (view.mapping) {
    mapping.value = { ...view.mapping }
    delete mapping.value.dimensions
  } else {
    mapping.value = autoMapImportHeaders(headers.value)
  }
  step.value = allMapped.value ? 3 : 2
}

function applyResult(result) {
  if (!result) return
  if (result.task_status === 'failed' || result.file_status === 'failed') {
    pipelineStatus.value = 'failed'
    resultSummary.value = result.error_message || '导入任务失败，请刷新后重试'
    resultSummaryClass.value = 'bg-red-50 text-red-700'
    stopPolling()
    return
  }
  if (result.projection_status === 'projection_failed') {
    pipelineStatus.value = 'failed'
    resultSummary.value = result.error_message || 'CSV 已导入，但后台分析失败，请稍后刷新或联系管理员'
    resultSummaryClass.value = 'bg-red-50 text-red-700'
    stopPolling()
    return
  }
  if (result.projection_status === 'projected') {
    pipelineStatus.value = 'completed'
    const imported = result.imported_count ?? 0
    const dup = result.duplicate_count ?? 0
    resultSummary.value = `成功导入 ${imported} 条${dup ? `，跳过重复 ${dup} 条` : ''}；主题分类与 L2 标注已完成，可前往仪表盘查看。`
    resultSummaryClass.value = 'bg-emerald-50 text-emerald-700'
    stopPolling()
    return
  }
  if (result.task_status === 'queued' || result.task_status === 'running') {
    pipelineStatus.value = 'importing'
    resultSummary.value = '正在写入反馈数据…'
    resultSummaryClass.value = 'bg-amber-50 text-amber-700'
    startPolling()
    return
  }
  if (result.task_status === 'succeeded' || result.file_status === 'processed') {
    if (result.projection_status === 'projecting' || result.projection_status === 'pending') {
      pipelineStatus.value = 'projecting'
      const imported = result.imported_count ?? 0
      resultSummary.value = `已导入 ${imported} 条，正在进行主题分类与 L2 表达标注…`
      resultSummaryClass.value = 'bg-violet-50 text-violet-700'
      startPolling()
      return
    }
  }
  if (result.task_id) {
    pipelineStatus.value = 'importing'
    startPolling()
  }
}

async function fetchJson(path) {
  const response = await fetch(path)
  if (response.status === 204) return null
  const body = await response.json()
  if (!response.ok) throw new Error(body?.error?.message || `请求失败（${response.status}）`)
  return body
}

async function refreshResult() {
  if (!store.workspaceId || !fileId.value) return
  try {
    const result = await fetchJson(baseUrl() + '/' + fileId.value + '/result')
    applyResult(result)
  } catch (error) {
    resultSummary.value = error.message
    resultSummaryClass.value = 'bg-red-50 text-red-700'
  }
}

function startPolling() {
  if (pollTimer) return
  pollTimer = window.setInterval(refreshResult, 2500)
}

function stopPolling() {
  if (pollTimer) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
  importing.value = false
}

async function restoreFromBackend() {
  if (!store.workspaceId) return
  stopPolling()
  pipelineStatus.value = 'idle'
  resultSummary.value = ''
  try {
    const latest = await fetchJson(baseUrl() + '/latest')
    if (!latest) {
      step.value = 1
      return
    }
    applyFileView(latest)
    await refreshResult()
    if (pipelineStatus.value === 'idle' && latest.status === 'processed') {
      pipelineStatus.value = 'completed'
      await refreshResult()
    }
  } catch (error) {
    uploadMsg.value = '恢复导入状态失败: ' + error.message
    uploadOk.value = false
  }
}

async function uploadFile(file) {
  uploadMsg.value = '上传中...'; uploadOk.value = false
  stopPolling()
  pipelineStatus.value = 'idle'
  resultSummary.value = ''
  const form = new FormData(); form.append('file', file)
  const response = await fetch(baseUrl(), { method: 'POST', body: form })
  const data = await response.json()
  if (data.error) { uploadMsg.value = '上传失败: ' + data.error.message; return }
  applyFileView(data)
  uploadMsg.value = '上传成功，' + data.size_bytes + ' 字节，' + data.headers.length + ' 列'; uploadOk.value = true
}

async function submitMapping() {
  if (!allMapped.value) return
  const response = await fetch(baseUrl() + '/' + fileId.value + '/mapping', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mapping: { ...mapping.value, dimensions: {} } })
  })
  const data = await response.json()
  if (data.error) throw new Error(data.error.message)
  step.value = 3
}

async function startImport() {
  importing.value = true
  pipelineStatus.value = 'importing'
  resultSummary.value = '正在提交导入任务…'
  resultSummaryClass.value = 'bg-amber-50 text-amber-700'
  try {
    await submitMapping()
    const response = await fetch(baseUrl() + '/' + fileId.value + '/start', {
      method: 'POST', headers: { 'Idempotency-Key': 'import-' + Date.now() }
    })
    const data = await response.json()
    if (data.error) throw new Error(data.error.message)
    startPolling()
    await refreshResult()
  } catch (error) {
    pipelineStatus.value = 'failed'
    resultSummary.value = error.message
    resultSummaryClass.value = 'bg-red-50 text-red-700'
    importing.value = false
  }
}

function resetForNewUpload() {
  stopPolling()
  step.value = 1
  fileId.value = ''
  originalFilename.value = ''
  headers.value = []
  mapping.value = {}
  uploadMsg.value = ''
  pipelineStatus.value = 'idle'
  resultSummary.value = ''
}

const onDrop = e => uploadFile(e.dataTransfer.files[0])
const onFileChange = e => { if (e.target.files[0]) uploadFile(e.target.files[0]) }

onMounted(restoreFromBackend)
watch(() => store.workspaceId, restoreFromBackend)
onUnmounted(stopPolling)
</script>
