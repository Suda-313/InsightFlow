import { defineStore } from 'pinia'
import { ref } from 'vue'

// 对话状态只缓存服务端副本；刷新恢复的唯一事实来源是 Workspace 下的会话接口。
export const useChatStore = defineStore('chat', () => {
  // 会话列表用于选择历史会话，服务端已排除归档记录。
  const sessions = ref([])
  // 当前活动会话只保存 public_id，绝不在浏览器保存数据库内部 id。
  const activeSessionId = ref('')
  // 页面显示的消息由服务端历史接口重建，不依赖 localStorage。
  const messages = ref([])

  // 所有聊天请求统一从工作区路径构建，避免遗漏 workspace 隔离边界。
  function baseUrl(workspaceId) {
    return '/api/v1/workspaces/' + workspaceId + '/chat'
  }

  // 非 2xx 响应必须中止后续渲染，避免把错误 JSON 当成正常消息。
  async function readJson(response) {
    if (!response.ok) throw new Error('聊天服务请求失败')
    return response.json()
  }

  // 将 API 时间转换为页面展示格式；缺失时间保持为空而不是伪造当前时间。
  function displayMessage(message) {
    return {
      id: message.id,
      role: message.role,
      content: message.content,
      // 历史消息不保存证据快照；当前请求返回的受控证据仅附加到当次助手消息，刷新后仍可通过 Trace 审计复核。
      evidence: message.evidence || [],
      time: message.created_at
        ? new Date(message.created_at).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
        : ''
    }
  }

  // 切换会话时重新读取服务端记录，确保刷新和多标签页操作后不会展示陈旧内存状态。
  async function selectSession(workspaceId, sessionId) {
    activeSessionId.value = sessionId
    const response = await fetch(baseUrl(workspaceId) + '/sessions/' + sessionId + '/messages')
    const history = await readJson(response)
    messages.value = history.map(displayMessage)
  }

  // 新会话成功后立即成为活动会话，旧会话不会被客户端清空或覆盖。
  async function createSession(workspaceId) {
    const response = await fetch(baseUrl(workspaceId) + '/sessions', { method: 'POST' })
    const session = await readJson(response)
    sessions.value = [session, ...sessions.value]
    activeSessionId.value = session.id
    messages.value = []
    return session
  }

  // 刷新后优先恢复最近活动会话；第一次使用时再创建空会话。
  async function restore(workspaceId) {
    if (!workspaceId) return
    const response = await fetch(baseUrl(workspaceId) + '/sessions')
    sessions.value = await readJson(response)
    if (sessions.value.length === 0) {
      await createSession(workspaceId)
      return
    }
    await selectSession(workspaceId, sessions.value[0].id)
  }

  // “清空对话”语义是归档当前会话并启动新会话，避免误删已产生的业务问答记录。
  async function archiveAndStartNew(workspaceId) {
    if (activeSessionId.value) {
      const response = await fetch(baseUrl(workspaceId) + '/sessions/' + activeSessionId.value, { method: 'DELETE' })
      if (!response.ok) throw new Error('归档会话失败')
      sessions.value = sessions.value.filter(session => session.id !== activeSessionId.value)
    }
    return createSession(workspaceId)
  }

  // 发送后重新拉取历史，以服务端持久化顺序、时间和最终答案替换浏览器临时状态。
  async function send(workspaceId, text) {
    if (!activeSessionId.value) await createSession(workspaceId)
    const response = await fetch(baseUrl(workspaceId), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ session_id: activeSessionId.value, message: text })
    })
    const reply = await readJson(response)
    await selectSession(workspaceId, activeSessionId.value)
    // 服务端历史 API 只保存用户消息和最终回答；按本次最终文本定位末条助手消息，避免持久化模型推理或重复保存证据。
    const assistantMessage = [...messages.value].reverse()
      .find(message => message.role === 'assistant' && message.content === reply.content)
    if (assistantMessage) assistantMessage.evidence = reply.evidence || []
  }

  return {
    sessions,
    activeSessionId,
    messages,
    restore,
    selectSession,
    createSession,
    archiveAndStartNew,
    send
  }
})
