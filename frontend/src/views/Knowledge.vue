<template>
  <div class="p-6 max-w-7xl mx-auto">
    <div class="flex items-start justify-between gap-4 mb-6">
      <div>
        <h1 class="text-xl font-bold">企业知识库</h1>
        <p class="text-xs text-slate-500 mt-1">上传 Markdown 或 TXT 后先进入待审核；只有发布版本会被 AI 检索和引用。支持一次选择多个文件；批量上传时会读取各文件 YAML front matter 中的 title、document_type、effective_from 等字段。</p>
      </div>
      <div class="flex items-center gap-2 shrink-0">
        <button
          v-if="pendingPublishTargets.length"
          class="px-3 py-2 text-sm rounded border border-emerald-200 text-emerald-700 hover:bg-emerald-50 disabled:opacity-50"
          :disabled="bulkPublishing || loading || uploading"
          @click="beginBulkPublish"
        >
          {{ bulkPublishing ? `发布中 ${bulkPublishProgress.done}/${bulkPublishProgress.total}…` : `一键发布 ${pendingPublishTargets.length} 个待审核` }}
        </button>
        <button class="btn-primary" :disabled="loading" @click="loadDocuments">{{ loading ? '加载中…' : '刷新列表' }}</button>
      </div>
    </div>

    <div v-if="requestError" class="mb-4 px-3 py-2 rounded-lg bg-red-50 text-sm text-red-700">{{ requestError }}</div>

    <section class="card p-5 mb-6">
      <h2 class="font-semibold text-sm mb-4">上传知识文档</h2>
      <div class="grid grid-cols-1 md:grid-cols-4 gap-3 items-end">
        <label class="text-xs text-slate-500">文档标题
          <input v-model.trim="title" class="mt-1 w-full rounded border border-slate-200 px-3 py-2 text-sm bg-white" maxlength="200" placeholder="单文件必填；批量时作默认值">
        </label>
        <label class="text-xs text-slate-500">文档类型
          <select v-model="type" class="mt-1 w-full rounded border border-slate-200 px-3 py-2 text-sm bg-white">
            <option value="RELEASE_NOTE">版本公告</option>
            <option value="KNOWN_ISSUE">已知问题</option>
            <option value="SUPPORT_SOP">客服 SOP</option>
            <option value="SENTIMENT_PLAYBOOK">舆情处置手册</option>
            <option value="OPERATION_EVENT">运营事件</option>
            <option value="POSTMORTEM">运营复盘</option>
          </select>
        </label>
        <label class="text-xs text-slate-500">适用范围
          <select v-model="scope" class="mt-1 w-full rounded border border-slate-200 px-3 py-2 text-sm bg-white">
            <option value="WORKSPACE">当前 Workspace 专属</option>
            <option value="ORGANIZATION">组织通用</option>
          </select>
        </label>
        <label class="text-xs text-slate-500 md:col-span-1">文件（.md / .markdown / .txt，可多选）
          <input type="file" accept=".md,.markdown,.txt,text/markdown,text/plain" multiple class="mt-1 block w-full text-sm" @change="selectFiles">
        </label>
      </div>
      <div class="flex items-start justify-between gap-4 mt-4">
        <div class="text-xs text-slate-400 min-w-0 flex-1">
          <p v-if="!selectedFiles.length">尚未选择文件</p>
          <p v-else>已选择 {{ selectedFiles.length }} 个文件</p>
          <ul v-if="selectedFiles.length" class="mt-1 max-h-24 overflow-y-auto space-y-0.5">
            <li v-for="file in selectedFiles" :key="file.name + file.size" class="truncate">{{ file.name }}</li>
          </ul>
          <p v-if="uploadSummary" class="mt-2" :class="uploadSummary.failed ? 'text-amber-600' : 'text-emerald-600'">{{ uploadSummary.text }}</p>
        </div>
        <button class="btn-primary shrink-0" :disabled="uploading || !canUpload" @click="uploadDocuments">{{ uploadButtonLabel }}</button>
      </div>
    </section>

    <section class="card overflow-hidden">
      <div class="px-5 py-4 border-b border-slate-100 flex items-center justify-between">
        <h2 class="font-semibold text-sm">可见文档</h2><span class="text-xs text-slate-400">仅展示组织通用和当前 Workspace 专属资料</span>
      </div>
      <div v-if="loading" class="p-8 text-center text-sm text-slate-400">正在加载知识文档…</div>
      <div v-else-if="!documents.length" class="p-8 text-center text-sm text-slate-400">暂无可见知识文档</div>
      <div v-else class="divide-y divide-slate-100">
        <article v-for="document in documents" :key="document.id" class="p-5">
          <div class="flex flex-wrap items-start justify-between gap-3">
            <div><h3 class="font-medium">{{ document.title }}</h3><p class="text-xs text-slate-500 mt-1">{{ typeText(document.type) }} · {{ scopeText(document.scope) }}</p></div>
            <div class="flex items-center gap-3 text-xs">
              <span class="text-slate-400">{{ document.versions?.length || 0 }} 个版本</span>
              <button class="text-primary hover:underline" :disabled="Boolean(appendUploading[document.id])" @click="triggerAppendUpload(document.id)">
                {{ appendUploading[document.id] ? '上传中…' : '上传新版本' }}
              </button>
            </div>
          </div>
          <p v-if="appendError[document.id]" class="mt-2 text-xs text-red-600">{{ appendError[document.id] }}</p>
          <div class="mt-4 space-y-2">
            <div v-for="version in document.versions" :key="version.id" class="rounded-lg bg-slate-50 px-3 py-2 flex flex-wrap items-center justify-between gap-3">
              <div class="text-sm"><span class="font-mono text-xs text-slate-500">v{{ version.version_no }}</span><span class="ml-2" :class="statusClass(version.status)">{{ statusText(version.status) }}</span><span class="ml-2 text-xs text-slate-400">{{ version.source_name }} · {{ formatTime(version.created_at) }}</span></div>
              <div class="flex items-center gap-3 text-xs">
                <button class="text-primary hover:underline" :disabled="isVersionPending(version.id)" @click="downloadSource(document.id, version.id, version.source_name)">{{ isOperationPending(version.id, 'source') ? '下载中…' : '查看原文' }}</button>
                <button v-if="version.status === 'PENDING_REVIEW'" class="text-emerald-600 hover:underline" :disabled="isVersionPending(version.id)" @click="beginPublish(document, version)">{{ isActionPending(version.id) ? '处理中…' : '发布' }}</button>
                <button v-if="version.status === 'PUBLISHED'" class="text-amber-600 hover:underline" :disabled="isVersionPending(version.id)" @click="runAction(document.id, version.id, 'expire', 'POST')">{{ isActionPending(version.id) ? '处理中…' : '失效' }}</button>
                <button v-if="version.status !== 'PUBLISHED' && version.status !== 'DELETED'" class="text-red-600 hover:underline" :disabled="isVersionPending(version.id)" @click="runAction(document.id, version.id, '', 'DELETE')">{{ isActionPending(version.id) ? '处理中…' : '删除' }}</button>
              </div>
              <p v-if="actionError[version.id]" class="mt-2 text-xs text-red-600">{{ actionError[version.id] }}</p>
            </div>
          </div>
        </article>
      </div>
    </section>

    <div v-if="bulkPublishDialog" class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" @click.self="closeBulkPublishDialog">
      <div class="card w-full max-w-md p-5 shadow-xl" role="dialog" aria-modal="true" aria-labelledby="bulk-publish-dialog-title">
        <h3 id="bulk-publish-dialog-title" class="font-semibold text-sm mb-2">一键发布 {{ bulkPublishDialog.targets.length }} 个待审核版本？</h3>
        <p class="text-xs text-slate-500 mb-4">将依次完成切片与嵌入，耗时取决于文档长度。默认保留各文档已有的已发布版本；若只需最新版参与检索，可勾选下线旧版。</p>
        <label v-if="bulkPublishDialog.hasPublishedVersions" class="flex items-start gap-2 text-sm mb-4 cursor-pointer">
          <input v-model="expirePreviousPublishedBulk" type="checkbox" class="mt-0.5">
          <span>各文档同时下线旧版已发布版本</span>
        </label>
        <p v-if="bulkPublishSummary" class="text-xs mb-4" :class="bulkPublishSummary.failed ? 'text-amber-600' : 'text-emerald-600'">{{ bulkPublishSummary.text }}</p>
        <div class="flex justify-end gap-2">
          <button class="px-3 py-1.5 text-sm rounded border border-slate-200" :disabled="bulkPublishing" @click="closeBulkPublishDialog">取消</button>
          <button class="btn-primary text-sm" :disabled="bulkPublishing" @click="confirmBulkPublish">{{ bulkPublishing ? '发布中…' : '确认发布' }}</button>
        </div>
      </div>
    </div>

    <div v-if="publishDialog" class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" @click.self="closePublishDialog">
      <div class="card w-full max-w-md p-5 shadow-xl" role="dialog" aria-modal="true" aria-labelledby="publish-dialog-title">
        <h3 id="publish-dialog-title" class="font-semibold text-sm mb-2">发布 v{{ publishDialog.versionNo }}？</h3>
        <p class="text-xs text-slate-500 mb-4">发布后该版本会进入 AI 检索。默认保留旧版已发布内容；若只需最新版参与检索，可勾选下线旧版。</p>
        <label v-if="publishDialog.publishedLabels.length" class="flex items-start gap-2 text-sm mb-4 cursor-pointer">
          <input v-model="expirePreviousPublished" type="checkbox" class="mt-0.5">
          <span>同时下线旧版（{{ publishDialog.publishedLabels.join('、') }} 将不再被 AI 检索）</span>
        </label>
        <p v-else class="text-xs text-slate-400 mb-4">该文档尚无其他已发布版本。</p>
        <div class="flex justify-end gap-2">
          <button class="px-3 py-1.5 text-sm rounded border border-slate-200" :disabled="publishDialog.submitting" @click="closePublishDialog">取消</button>
          <button class="btn-primary text-sm" :disabled="publishDialog.submitting" @click="confirmPublish">{{ publishDialog.submitting ? '发布中…' : '确认发布' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useWorkspaceStore } from '../stores/workspace'
import { buildUploadFormData, parseFrontMatter } from '../utils/knowledge-upload'

// 当前 Workspace 是所有知识读写的服务端范围入口；页面不会也不能提交其他 Workspace 的标识。
const store = useWorkspaceStore()
const documents = ref([]), title = ref(''), type = ref('RELEASE_NOTE'), scope = ref('WORKSPACE')
const selectedFiles = ref([]), loading = ref(false), uploading = ref(false), uploadProgress = ref({ done: 0, total: 0 })
const uploadSummary = ref(null), pendingOperations = ref({}), requestError = ref(''), actionError = ref({})
const appendUploading = ref({}), appendError = ref({})
const publishDialog = ref(null), expirePreviousPublished = ref(false)
const bulkPublishDialog = ref(null), bulkPublishing = ref(false), bulkPublishProgress = ref({ done: 0, total: 0 })
const expirePreviousPublishedBulk = ref(false), bulkPublishSummary = ref(null)

const pendingPublishTargets = computed(() => {
  const targets = []
  for (const document of documents.value) {
    for (const version of document.versions || []) {
      if (version.status === 'PENDING_REVIEW') {
        targets.push({
          documentId: document.id,
          documentTitle: document.title,
          versionId: version.id,
          versionNo: version.version_no,
          hasPublished: (document.versions || []).some(item => item.status === 'PUBLISHED' && item.version_no !== version.version_no),
        })
      }
    }
  }
  return targets
})

const canUpload = computed(() => Boolean(store.workspaceId && selectedFiles.value.length && !uploading.value))

const uploadButtonLabel = computed(() => {
  if (!uploading.value) {
    const count = selectedFiles.value.length
    return count > 1 ? `批量上传 ${count} 个待审核版本` : '上传待审核版本'
  }
  if (uploadProgress.value.total > 1) {
    return `上传中 ${uploadProgress.value.done}/${uploadProgress.value.total}…`
  }
  return '上传中…'
})

// 非 2xx 响应必须展示明确错误，不能把发布失败的版本误显示为已发布。
async function fetchWithTimeout(path, options = {}) {
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), 60000)
  try { return await fetch(path, { ...options, signal: controller.signal }) }
  catch (error) {
    if (error.name === 'AbortError') throw new Error('请求超时，请稍后刷新列表确认结果')
    throw error
  } finally { window.clearTimeout(timeout) }
}

async function request(path, options) {
  const response = await fetchWithTimeout(path, options)
  const body = await response.json().catch(() => null)
  if (!response.ok) throw new Error(body?.error?.message || `请求失败（${response.status}）`)
  return body
}

// 所有知识接口都从当前 Workspace UUID 拼接，切换业务空间后会重新加载可见范围。
function baseUrl() { return '/api/v1/workspaces/' + store.workspaceId + '/knowledge/documents' }

async function loadDocuments() {
  if (!store.workspaceId) return
  loading.value = true; requestError.value = ''
  try { const result = await request(baseUrl()); documents.value = Array.isArray(result) ? result : [] }
  catch (error) { documents.value = []; requestError.value = error.message }
  finally { loading.value = false }
}

function selectFiles(event) {
  selectedFiles.value = Array.from(event.target.files || [])
  uploadSummary.value = null
  event.target.value = ''
}

async function uploadDocuments() {
  if (!store.workspaceId || !selectedFiles.value.length || uploading.value) return
  uploading.value = true
  requestError.value = ''
  uploadSummary.value = null
  const files = [...selectedFiles.value]
  uploadProgress.value = { done: 0, total: files.length }
  const defaults = { title: title.value, type: type.value, scope: scope.value }
  const errors = []
  let succeeded = 0

  for (const file of files) {
    try {
      const frontMatter = parseFrontMatter(await file.text())
      if (files.length === 1 && !frontMatter.title && !defaults.title.trim()) {
        throw new Error('请填写文档标题，或在 Markdown 顶部添加 title 字段')
      }
      const form = buildUploadFormData(file, defaults, frontMatter)
      if (!form.get('title')) {
        throw new Error('无法确定文档标题')
      }
      await request(baseUrl(), { method: 'POST', body: form })
      succeeded += 1
    } catch (error) {
      errors.push(`${file.name}: ${error.message}`)
    } finally {
      uploadProgress.value = { done: uploadProgress.value.done + 1, total: files.length }
    }
  }

  if (errors.length) {
    uploadSummary.value = {
      failed: true,
      text: `成功 ${succeeded} 个，失败 ${errors.length} 个：${errors.slice(0, 3).join('；')}${errors.length > 3 ? '…' : ''}`,
    }
    requestError.value = errors.join('\n')
  } else {
    uploadSummary.value = { failed: false, text: `已成功上传 ${succeeded} 个待审核版本` }
    title.value = ''
    selectedFiles.value = []
  }
  uploading.value = false
  await loadDocuments()
}

// 动态创建 file input，避免 :ref 回调写入 reactive 状态触发无限重渲染。
function triggerAppendUpload(documentId) {
  if (appendUploading.value[documentId]) return
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = '.md,.markdown,.txt,text/markdown,text/plain'
  input.style.display = 'none'
  input.addEventListener('change', (event) => {
    onAppendFileSelected(documentId, event)
    input.remove()
  })
  document.body.appendChild(input)
  input.click()
}

async function onAppendFileSelected(documentId, event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file || appendUploading.value[documentId]) return
  appendUploading.value = { ...appendUploading.value, [documentId]: true }
  appendError.value = { ...appendError.value, [documentId]: '' }
  try {
    const form = new FormData()
    form.append('file', file)
    await request(baseUrl() + '/' + documentId + '/versions', { method: 'POST', body: form })
    await loadDocuments()
  } catch (error) {
    appendError.value = { ...appendError.value, [documentId]: error.message }
  } finally {
    const { [documentId]: ignored, ...remaining } = appendUploading.value
    appendUploading.value = remaining
  }
}

function publishedVersionLabels(document, pendingVersion) {
  return (document.versions || [])
      .filter(item => item.status === 'PUBLISHED' && item.version_no !== pendingVersion.version_no)
      .map(item => 'v' + item.version_no)
}

function beginPublish(document, version) {
  if (isVersionPending(version.id)) return
  publishDialog.value = {
    documentId: document.id,
    versionId: version.id,
    versionNo: version.version_no,
    publishedLabels: publishedVersionLabels(document, version),
    submitting: false,
  }
  expirePreviousPublished.value = false
}

function closePublishDialog() {
  if (publishDialog.value?.submitting) return
  publishDialog.value = null
  expirePreviousPublished.value = false
}

async function confirmPublish() {
  if (!publishDialog.value || publishDialog.value.submitting) return
  const { documentId, versionId, publishedLabels } = publishDialog.value
  publishDialog.value = { ...publishDialog.value, submitting: true }
  beginOperation(versionId, 'publish'); clearActionError(versionId)
  try {
    await request(baseUrl() + '/' + documentId + '/versions/' + versionId + '/publish', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ expire_previous_published: publishedLabels.length > 0 && expirePreviousPublished.value }),
    })
    closePublishDialog()
    await loadDocuments()
  } catch (error) {
    publishDialog.value = { ...publishDialog.value, submitting: false }
    actionError.value = { ...actionError.value, [versionId]: error.message }
  } finally {
    endOperation(versionId)
  }
}

function beginBulkPublish() {
  if (!pendingPublishTargets.value.length || bulkPublishing.value) return
  bulkPublishSummary.value = null
  expirePreviousPublishedBulk.value = false
  bulkPublishDialog.value = {
    targets: [...pendingPublishTargets.value],
    hasPublishedVersions: pendingPublishTargets.value.some(item => item.hasPublished),
  }
}

function closeBulkPublishDialog() {
  if (bulkPublishing.value) return
  bulkPublishDialog.value = null
  bulkPublishSummary.value = null
  expirePreviousPublishedBulk.value = false
}

async function confirmBulkPublish() {
  if (!bulkPublishDialog.value || bulkPublishing.value) return
  bulkPublishing.value = true
  bulkPublishSummary.value = null
  requestError.value = ''
  const targets = [...bulkPublishDialog.value.targets]
  bulkPublishProgress.value = { done: 0, total: targets.length }
  const errors = []
  let succeeded = 0

  for (const target of targets) {
    beginOperation(target.versionId, 'publish')
    clearActionError(target.versionId)
    try {
      await request(baseUrl() + '/' + target.documentId + '/versions/' + target.versionId + '/publish', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          expire_previous_published: expirePreviousPublishedBulk.value && target.hasPublished,
        }),
      })
      succeeded += 1
    } catch (error) {
      errors.push(`${target.documentTitle} v${target.versionNo}: ${error.message}`)
      actionError.value = { ...actionError.value, [target.versionId]: error.message }
    } finally {
      bulkPublishProgress.value = { done: bulkPublishProgress.value.done + 1, total: targets.length }
      endOperation(target.versionId)
    }
  }

  if (errors.length) {
    bulkPublishSummary.value = {
      failed: true,
      text: `成功 ${succeeded} 个，失败 ${errors.length} 个：${errors.slice(0, 2).join('；')}${errors.length > 2 ? '…' : ''}`,
    }
    requestError.value = errors.join('\n')
  } else {
    bulkPublishSummary.value = { failed: false, text: `已成功发布 ${succeeded} 个版本` }
    bulkPublishDialog.value = null
    expirePreviousPublishedBulk.value = false
  }
  bulkPublishing.value = false
  await loadDocuments()
}

// 同一版本的动作互斥，其他版本仍可操作；超时会解锁当前行并提示用户刷新确认最终状态。
function isVersionPending(versionId) { return Boolean(pendingOperations.value[versionId]) }
function isOperationPending(versionId, operation) { return pendingOperations.value[versionId] === operation }
function isActionPending(versionId) { return isVersionPending(versionId) && !isOperationPending(versionId, 'source') }
function beginOperation(versionId, operation) { pendingOperations.value = { ...pendingOperations.value, [versionId]: operation } }
function endOperation(versionId) {
  const { [versionId]: ignored, ...remaining } = pendingOperations.value
  pendingOperations.value = remaining
}
function clearActionError(versionId) {
  const { [versionId]: ignored, ...remaining } = actionError.value
  actionError.value = remaining
}

// 失效和删除均在一次操作后重拉服务端状态，避免前端猜测版本实际生命周期。
async function runAction(documentId, versionId, action, method) {
  if (isVersionPending(versionId)) return
  beginOperation(versionId, action || 'delete'); clearActionError(versionId)
  try { await request(baseUrl() + '/' + documentId + '/versions/' + versionId + (action ? '/' + action : ''), { method }); await loadDocuments() }
  catch (error) { actionError.value = { ...actionError.value, [versionId]: error.message } } finally { endOperation(versionId) }
}

// Authenticated fetch supplies the session token before the browser receives the source bytes.
async function downloadSource(documentId, versionId, sourceName) {
  if (isVersionPending(versionId)) return
  beginOperation(versionId, 'source'); clearActionError(versionId)
  try {
    const response = await fetchWithTimeout(sourceUrl(documentId, versionId))
    if (!response.ok) {
      const body = await response.json().catch(() => null)
      throw new Error(body?.error?.message || `请求失败（${response.status}）`)
    }
    const objectUrl = URL.createObjectURL(await response.blob())
    const link = document.createElement('a')
    link.href = objectUrl
    link.download = sourceName || 'knowledge.txt'
    link.click()
    URL.revokeObjectURL(objectUrl)
  } catch (error) {
    actionError.value = { ...actionError.value, [versionId]: error.message }
  } finally {
    endOperation(versionId)
  }
}

// Source URLs remain internal; this helper is called through authenticated fetch instead of anchor navigation.
function sourceUrl(documentId, versionId) { return baseUrl() + '/' + documentId + '/versions/' + versionId + '/source' }
function typeText(value) {
  return ({
    RELEASE_NOTE: '版本公告',
    KNOWN_ISSUE: '已知问题',
    SUPPORT_SOP: '客服 SOP',
    SENTIMENT_PLAYBOOK: '舆情处置手册',
    OPERATION_EVENT: '运营事件',
    POSTMORTEM: '运营复盘',
  })[value] || value
}
function scopeText(value) { return value === 'ORGANIZATION' ? '组织通用' : '当前 Workspace 专属' }
function statusText(value) { return ({ PENDING_REVIEW: '待审核', PUBLISHED: '已发布', EXPIRED: '已失效', DELETED: '已删除' })[value] || value }
function statusClass(value) { return ({ PENDING_REVIEW: 'text-amber-600', PUBLISHED: 'text-emerald-600', EXPIRED: 'text-slate-500', DELETED: 'text-red-600' })[value] || 'text-slate-500' }
function formatTime(value) { return value ? value.slice(0, 16).replace('T', ' ') : '-' }

onMounted(loadDocuments)
watch(() => store.workspaceId, loadDocuments)
</script>
