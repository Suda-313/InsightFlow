<template>
  <main class="min-h-screen bg-slate-50 flex items-center justify-center p-6">
    <section class="w-full max-w-md bg-white rounded-2xl border border-slate-200 shadow-sm p-7">
      <div class="mb-6"><h1 class="text-2xl font-bold">登录 InsightFlow</h1><p class="text-sm text-slate-500 mt-2">使用本地账号进入已授权的工作区。</p></div>
      <form class="space-y-4" @submit.prevent="submit">
        <label class="block text-sm font-medium">用户名<input v-model.trim="username" autocomplete="username" class="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 outline-none focus:border-primary" required /></label>
        <label class="block text-sm font-medium">密码<input v-model="password" type="password" autocomplete="current-password" class="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 outline-none focus:border-primary" required /></label>
        <label class="block text-sm font-medium">首次初始化口令 <span class="font-normal text-slate-400">（仅首次 Owner）</span><input v-model="bootstrapToken" type="password" class="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 outline-none focus:border-primary" /></label>
        <p v-if="error" class="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">{{ error }}</p>
        <button :disabled="loading" class="w-full rounded-lg bg-primary px-4 py-2.5 font-medium text-white disabled:opacity-50">{{ loading ? '处理中…' : bootstrapToken ? '初始化并登录' : '登录' }}</button>
      </form>
      <p class="mt-5 text-xs leading-5 text-slate-500">首次启动请在本地配置中设置 bootstrap-token；成功创建首个 Owner 后，该口令不会再生效。</p>
    </section>
  </main>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { setAccessToken } from '../lib/auth'

const router = useRouter()
const username = ref(''), password = ref(''), bootstrapToken = ref(''), error = ref(''), loading = ref(false)

/** 登录与首次初始化共用最小账号字段；仅在用户明确填写初始化口令时调用 bootstrap。 */
async function submit() {
  loading.value = true; error.value = ''
  try {
    const endpoint = bootstrapToken.value ? '/api/v1/auth/bootstrap' : '/api/v1/auth/login'
    const payload = { username: username.value, password: password.value }
    if (bootstrapToken.value) payload.bootstrapToken = bootstrapToken.value
    const response = await fetch(endpoint, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) })
    const data = await response.json()
    if (!response.ok) throw new Error(data?.error?.message || '登录失败，请检查账号或配置')
    setAccessToken(data.accessToken)
    await router.replace('/')
  } catch (exception) { error.value = exception.message || '登录失败，请稍后重试' } finally { loading.value = false }
}
</script>
