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
      <div v-if="uploadMsg" class="mt-4 text-center text-sm" :class="uploadOk ? 'text-green-500' : 'text-red-500'">{{ uploadMsg }}</div>
    </div>

    <!-- Step 2: Mapping -->
    <div v-if="step === 2" class="card p-5">
      <h3 class="font-semibold mb-3">字段映射</h3>
      <div class="space-y-3 max-w-md">
        <div v-for="f in fields" :key="f.key" class="flex items-center gap-2">
          <label class="text-xs text-slate-500 w-24 shrink-0">{{ f.label }}</label>
          <select v-model="mapping[f.key]" class="flex-1 bg-slate-100 dark:bg-slate-700 rounded-lg px-2 py-1.5 text-sm border-0 outline-none">
            <option value="">-- 选择列 --</option>
            <option v-for="h in headers" :key="h" :value="h">{{ h }}</option>
          </select>
        </div>
      </div>
      <button @click="submitMapping" class="btn-primary mt-4">确认映射</button>
    </div>

    <!-- Step 3: Start -->
    <div v-if="step === 3" class="card p-5 text-center">
      <p class="text-sm text-green-500 mb-4">✅ 映射保存成功！</p>
      <button @click="startImport" :disabled="importing" class="btn-accent px-6 py-2.5">{{ importing ? '导入中...' : '开始导入' }}</button>
      <p v-if="importDone" class="text-sm text-green-500 mt-4">✅ 导入任务已启动，正在后台处理...</p>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { UploadCloud } from 'lucide-vue-next'
import { useWorkspaceStore } from '../stores/workspace'
const store = useWorkspaceStore()

const step = ref(1), headers = ref([]), fileId = ref(''), uploadMsg = ref(''), uploadOk = ref(false), importing = ref(false), importDone = ref(false)
const fields = [{ key: 'feedback_text', label: '反馈内容' }, { key: 'occurred_at', label: '发生时间' }, { key: 'source', label: '来源' }, { key: 'external_ref', label: '工单号' }]
const mapping = ref({})

async function uploadFile(file) {
  uploadMsg.value = '上传中...'; uploadOk.value = false
  let form = new FormData(); form.append('file', file)
  let r = await fetch('/api/v1/workspaces/' + store.workspaceId + '/imports/files', { method: 'POST', body: form })
  let d = await r.json()
  if (d.error) { uploadMsg.value = '上传失败: ' + d.error.message; return }
  fileId.value = d.id; headers.value = d.headers
  uploadMsg.value = '✅ 上传成功！' + d.size_bytes + ' 字节，' + d.headers.length + ' 列'; uploadOk.value = true
  // auto match
  headers.value.forEach(h => {
    if (h.includes('反馈')) mapping.value.feedback_text = h
    if (h.includes('时间')) mapping.value.occurred_at = h
    if (h.includes('来源')) mapping.value.source = h
    if (h.includes('工单')) mapping.value.external_ref = h
  })
  step.value = 2
}

async function submitMapping() {
  let r = await fetch('/api/v1/workspaces/' + store.workspaceId + '/imports/files/' + fileId.value + '/mapping', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mapping: { ...mapping.value, dimensions: {} } })
  })
  let d = await r.json()
  if (d.error) { alert('映射失败: ' + d.error.message); return }
  step.value = 3
}

async function startImport() {
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
