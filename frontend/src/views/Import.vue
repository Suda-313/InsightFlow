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

    <!-- Step 3: Ready to start -->
    <div v-if="step === 3" class="card p-6">
      <div class="flex items-center gap-3 mb-4">
        <div class="w-10 h-10 rounded-xl bg-emerald-100 dark:bg-emerald-900 flex items-center justify-center"><CheckCircle class="w-5 h-5 text-emerald-600" /></div>
        <div><h3 class="font-semibold">已自动识别列映射</h3><p class="text-xs text-slate-500">点击下方按钮开始导入</p></div>
      </div>
      <div class="grid grid-cols-2 gap-2 mb-4 text-sm">
        <div v-for="f in fields" :key="f.key" class="flex items-center justify-between p-2 bg-slate-50 dark:bg-slate-800 rounded-lg">
          <span class="text-slate-500">{{ f.label }}</span>
          <span class="font-mono text-xs">{{ mapping[f.key] }}</span>
        </div>
      </div>
      <div class="flex gap-2">
        <button @click="startImport" :disabled="importing" class="btn-accent px-6 py-2.5">{{ importing ? '导入中...' : '开始导入' }}</button>
        <button @click="step = 2" class="text-sm text-slate-500 hover:text-slate-700 px-3 py-2">修改映射</button>
      </div>
      <p v-if="importDone" class="text-sm text-emerald-500 mt-4 flex items-center gap-1"><CheckCircle class="w-4 h-4" />导入完成，正在后台处理...</p>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { UploadCloud, CheckCircle, AlertCircle } from 'lucide-vue-next'
import { useWorkspaceStore } from '../stores/workspace'
const store = useWorkspaceStore()

const step = ref(1), headers = ref([]), fileId = ref(''), uploadMsg = ref(''), uploadOk = ref(false), importing = ref(false), importDone = ref(false)
const fields = [{ key: 'feedback_text', label: '反馈内容' }, { key: 'occurred_at', label: '发生时间' }, { key: 'source', label: '来源' }, { key: 'external_ref', label: '工单号' }]
const CANONICAL_IMPORT_KEYS = ['feedback_text', 'occurred_at', 'source', 'external_ref']
const mapping = ref({})
const allMapped = computed(() => Object.values(mapping.value).filter(Boolean).length === 4)

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

async function uploadFile(file) {
  uploadMsg.value = '上传中...'; uploadOk.value = false
  let form = new FormData(); form.append('file', file)
  let r = await fetch('/api/v1/workspaces/' + store.workspaceId + '/imports/files', { method: 'POST', body: form })
  let d = await r.json()
  if (d.error) { uploadMsg.value = '上传失败: ' + d.error.message; return }
  fileId.value = d.id; headers.value = d.headers
  uploadMsg.value = '上传成功，' + d.size_bytes + ' 字节，' + d.headers.length + ' 列'; uploadOk.value = true

  mapping.value = autoMapImportHeaders(headers.value)
  step.value = allMapped.value ? 3 : 2
}

async function submitMapping() {
  if (!allMapped.value) return
  let r = await fetch('/api/v1/workspaces/' + store.workspaceId + '/imports/files/' + fileId.value + '/mapping', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mapping: { ...mapping.value, dimensions: {} } })
  })
  let d = await r.json()
  if (d.error) { alert('映射失败: ' + d.error.message); return }
  step.value = 3
}

async function startImport() {
  // First submit mapping if not yet done
  await submitMapping()
  importing.value = true
  let r = await fetch('/api/v1/workspaces/' + store.workspaceId + '/imports/files/' + fileId.value + '/start', {
    method: 'POST', headers: { 'Idempotency-Key': 'import-' + Date.now() }
  })
  let d = await r.json()
  if (d.error) { alert('导入失败: ' + d.error.message); importing.value = false; return }
  importDone.value = true
}

const onDrop = e => uploadFile(e.dataTransfer.files[0])
const onFileChange = e => { if (e.target.files[0]) uploadFile(e.target.files[0]) }
</script>