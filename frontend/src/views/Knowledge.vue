<template>
  <div class="p-6 max-w-7xl mx-auto">
    <div class="flex items-start justify-between gap-4 mb-6">
      <div>
        <h1 class="text-xl font-bold">企业知识库</h1>
        <p class="text-xs text-slate-500 mt-1">上传 Markdown 或 TXT 后先进入待审核；只有发布版本会被 AI 检索和引用。</p>
      </div>
      <button class="btn-primary" :disabled="loading" @click="loadDocuments">{{ loading ? '加载中…' : '刷新列表' }}</button>
    </div>

    <div v-if="requestError" class="mb-4 px-3 py-2 rounded-lg bg-red-50 text-sm text-red-700">{{ requestError }}</div>

    <section class="card p-5 mb-6">
      <h2 class="font-semibold text-sm mb-4">上传知识文档</h2>
      <div class="grid grid-cols-1 md:grid-cols-4 gap-3 items-end">
        <label class="text-xs text-slate-500">文档标题
          <input v-model.trim="title" class="mt-1 w-full rounded border border-slate-200 px-3 py-2 text-sm bg-white" maxlength="200" placeholder="例如：7 月版本公告">
        </label>
        <label class="text-xs text-slate-500">文档类型
          <select v-model="type" class="mt-1 w-full rounded border border-slate-200 px-3 py-2 text-sm bg-white">
            <option value="RELEASE_NOTE">版本公告</option>
            <option value="KNOWN_ISSUE">已知问题</option>
            <option value="SUPPORT_SOP">客服 SOP</option>
            <option value="SENTIMENT_PLAYBOOK">舆情处置手册</option>
          </select>
        </label>
        <label class="text-xs text-slate-500">适用范围
          <select v-model="scope" class="mt-1 w-full rounded border border-slate-200 px-3 py-2 text-sm bg-white">
            <option value="WORKSPACE">当前 Workspace 专属</option>
            <option value="ORGANIZATION">组织通用</option>
          </select>
        </label>
        <label class="text-xs text-slate-500">文件（.md / .markdown / .txt）
          <input type="file" accept=".md,.markdown,.txt,text/markdown,text/plain" class="mt-1 block w-full text-sm" @change="selectFile">
        </label>
      </div>
      <div class="flex items-center justify-between gap-4 mt-4">
        <p class="text-xs text-slate-400">{{ selectedFile ? selectedFile.name : '尚未选择文件' }}</p>
        <button class="btn-primary" :disabled="uploading || !canUpload" @click="uploadDocument">{{ uploading ? '上传中…' : '上传待审核版本' }}</button>
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
            <span class="text-xs text-slate-400">{{ document.versions?.length || 0 }} 个版本</span>
          </div>
          <div class="mt-4 space-y-2">
            <div v-for="version in document.versions" :key="version.id" class="rounded-lg bg-slate-50 px-3 py-2 flex flex-wrap items-center justify-between gap-3">
              <div class="text-sm"><span class="font-mono text-xs text-slate-500">v{{ version.version_no }}</span><span class="ml-2" :class="statusClass(version.status)">{{ statusText(version.status) }}</span><span class="ml-2 text-xs text-slate-400">{{ version.source_name }} · {{ formatTime(version.created_at) }}</span></div>
              <div class="flex items-center gap-3 text-xs">
                <button class="text-primary hover:underline" :disabled="isVersionPending(version.id)" @click="downloadSource(document.id, version.id, version.source_name)">{{ isOperationPending(version.id, 'source') ? '下载中…' : '查看原文' }}</button>
                <button v-if="version.status === 'PENDING_REVIEW'" class="text-emerald-600 hover:underline" :disabled="isVersionPending(version.id)" @click="runAction(document.id, version.id, 'publish', 'POST')">{{ isActionPending(version.id) ? '处理中…' : '发布' }}</button>
                <button v-if="version.status === 'PUBLISHED'" class="text-amber-600 hover:underline" :disabled="isVersionPending(version.id)" @click="runAction(document.id, version.id, 'expire', 'POST')">{{ isActionPending(version.id) ? '处理中…' : '失效' }}</button>
                <button v-if="version.status !== 'PUBLISHED' && version.status !== 'DELETED'" class="text-red-600 hover:underline" :disabled="isVersionPending(version.id)" @click="runAction(document.id, version.id, '', 'DELETE')">{{ isActionPending(version.id) ? '处理中…' : '删除' }}</button>
              </div>
              <p v-if="actionError[version.id]" class="mt-2 text-xs text-red-600">{{ actionError[version.id] }}</p>
            </div>
          </div>
        </article>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useWorkspaceStore } from '../stores/workspace'

// 当前 Workspace 是所有知识读写的服务端范围入口；页面不会也不能提交其他 Workspace 的标识。
const store = useWorkspaceStore()
const documents = ref([]), title = ref(''), type = ref('RELEASE_NOTE'), scope = ref('WORKSPACE')
const selectedFile = ref(null), loading = ref(false), uploading = ref(false), pendingOperations = ref({}), requestError = ref(''), actionError = ref({})

// 上传前只做体验层最小校验，文件类型和生命周期的最终判断仍由后端统一执行。
const canUpload = computed(() => store.workspaceId && title.value && selectedFile.value)

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

// 浏览器只保存用户刚选择的文件对象；刷新后以服务端版本列表为唯一事实来源。
function selectFile(event) { selectedFile.value = event.target.files?.[0] || null }

async function uploadDocument() {
  if (!canUpload.value || uploading.value) return
  uploading.value = true; requestError.value = ''
  try {
    const form = new FormData()
    form.append('title', title.value); form.append('type', type.value); form.append('scope', scope.value); form.append('file', selectedFile.value)
    await request(baseUrl(), { method: 'POST', body: form })
    title.value = ''; selectedFile.value = null; await loadDocuments()
  } catch (error) { requestError.value = error.message } finally { uploading.value = false }
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

// 发布、失效和删除均在一次操作后重拉服务端状态，避免前端猜测版本实际生命周期。
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
function typeText(value) { return ({ RELEASE_NOTE: '版本公告', KNOWN_ISSUE: '已知问题', SUPPORT_SOP: '客服 SOP', SENTIMENT_PLAYBOOK: '舆情处置手册' })[value] || value }
function scopeText(value) { return value === 'ORGANIZATION' ? '组织通用' : '当前 Workspace 专属' }
function statusText(value) { return ({ PENDING_REVIEW: '待审核', PUBLISHED: '已发布', EXPIRED: '已失效', DELETED: '已删除' })[value] || value }
function statusClass(value) { return ({ PENDING_REVIEW: 'text-amber-600', PUBLISHED: 'text-emerald-600', EXPIRED: 'text-slate-500', DELETED: 'text-red-600' })[value] || 'text-slate-500' }
function formatTime(value) { return value ? value.slice(0, 16).replace('T', ' ') : '-' }

onMounted(loadDocuments)
watch(() => store.workspaceId, loadDocuments)
</script>
