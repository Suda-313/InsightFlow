import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useChatStore = defineStore('chat', () => {
  const messages = ref([])

  function addMessage(msg) { messages.value.push(msg) }
  function updateLastAssistant(content, thinking) {
    const last = messages.value[messages.value.length - 1]
    if (last && last.role === 'assistant') {
      last.content = content
      if (thinking) last.thinking = thinking
    }
  }
  function clear() { messages.value = [] }

  return { messages, addMessage, updateLastAssistant, clear }
})