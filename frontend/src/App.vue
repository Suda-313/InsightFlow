<template>
  <router-view v-if="route.name === 'Login'" />
  <div v-else class="flex h-screen bg-slate-50 dark:bg-slate-900 text-slate-900 dark:text-slate-100 overflow-hidden">
    <aside class="w-56 bg-white dark:bg-slate-800 border-r border-slate-200 dark:border-slate-700 flex flex-col shrink-0">
      <div class="p-4 border-b border-slate-200 dark:border-slate-700">
        <router-link to="/" class="flex items-center gap-2.5 no-underline text-inherit">
          <div class="w-8 h-8 bg-gradient-to-br from-primary to-primary-light rounded-lg flex items-center justify-center"><Activity class="w-4 h-4 text-white" /></div>
          <span class="font-bold text-base">InsightFlow</span>
        </router-link>
      </div>
      <nav class="flex-1 p-2 space-y-0.5">
        <router-link to="/" class="nav-link" active-class="active"><MessageSquare class="w-4 h-4" /> 主页</router-link>
        <router-link to="/dashboard" class="nav-link" active-class="active"><LayoutDashboard class="w-4 h-4" /> 仪表盘</router-link>
        <router-link to="/data" class="nav-link" active-class="active"><BarChart3 class="w-4 h-4" /> 数据分析</router-link>
        <router-link to="/reports" class="nav-link" active-class="active"><FileText class="w-4 h-4" /> 分析报告</router-link>
        <router-link to="/investigations" class="nav-link" active-class="active"><ShieldAlert class="w-4 h-4" /> 调查中心</router-link>
        <router-link to="/knowledge" class="nav-link" active-class="active"><BookOpen class="w-4 h-4" /> 企业知识库</router-link>
        <router-link to="/evaluations" class="nav-link" active-class="active"><Gauge class="w-4 h-4" /> 评测基线</router-link>
      </nav>
      <div class="p-3 border-t border-slate-200 dark:border-slate-700">
        <form v-if="!store.workspaceId" class="mb-3 space-y-2" @submit.prevent="createWorkspace">
          <label class="block text-xs text-slate-500" for="first-workspace-name">&#21019;&#24314;&#31532;&#19968;&#20010;&#24037;&#20316;&#21306;</label>
          <input id="first-workspace-name" v-model="workspaceName" :disabled="workspaceCreating" maxlength="100" required class="w-full rounded border border-slate-300 px-2 py-1.5 text-xs text-slate-900" placeholder="&#20363;&#22914;&#65306;&#36229;&#33258;&#28982;&#34892;&#21160;&#32452;">
          <button type="submit" :disabled="workspaceCreating || !workspaceName.trim()" class="w-full rounded bg-primary px-2 py-1.5 text-xs font-medium text-white disabled:opacity-50">{{ workspaceCreating ? '&#21019;&#24314;&#20013;&#8230;' : '&#21019;&#24314;&#24037;&#20316;&#21306;' }}</button>
          <p v-if="workspaceError" class="text-xs text-red-600">{{ workspaceError }}</p>
        </form>
        <router-link to="/import" class="flex items-center gap-2 w-full px-3 py-2 rounded-lg bg-primary/10 text-primary text-sm font-medium hover:bg-primary/20 transition"><Upload class="w-4 h-4" /> 导入 CSV</router-link>
        <div class="mt-2 flex items-center justify-between gap-2 px-1"><span class="text-xs text-slate-400 truncate" v-if="store.workspaceId">{{ store.workspaceId.slice(0,8) }}</span><button class="text-xs text-slate-400 hover:text-slate-700" @click="logout">退出</button></div>
      </div>
    </aside>
    <main class="flex-1 overflow-auto"><router-view /></main>
  </div>
</template>

<script setup>
import { Activity, MessageSquare, BarChart3, LayoutDashboard, FileText, Upload, Gauge, BookOpen, ShieldAlert } from 'lucide-vue-next'
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useWorkspaceStore } from './stores/workspace'
import { clearAccessToken } from './lib/auth'

// 应用启动时只初始化默认 Workspace；各业务页面仍以请求路径中的 workspaceId 做服务端隔离。
const store = useWorkspaceStore()
const route = useRoute()
const router = useRouter()
// The name remains local so a failed request preserves the user's retry input.
const workspaceName = ref('')
const workspaceCreating = ref(false)
const workspaceError = ref('')
// 路由从登录页切换到业务页时，组件不会重建，因此需要在这里开始加载可见 Workspace。
// 登录页始终不发起业务 API，避免未登录时产生误导性报错。
watch(() => route.name, (name) => {
  if (name !== 'Login' && !store.workspaceId) store.init()
}, { immediate: true })

// The store writes the selected public ID only after the protected command succeeds.
async function createWorkspace() {
  if (workspaceCreating.value || !workspaceName.value.trim()) return
  workspaceCreating.value = true
  workspaceError.value = ''
  try {
    await store.createWorkspace(workspaceName.value.trim())
    workspaceName.value = ''
  } catch (error) {
    workspaceError.value = error.message || 'Workspace creation failed'
  } finally {
    workspaceCreating.value = false
  }
}

function logout() { clearAccessToken(); store.workspaceId = ''; router.replace('/login') }
</script>
