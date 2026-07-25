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
        <router-link to="/data" class="nav-link" active-class="active"><BarChart3 class="w-4 h-4" /> 数据分析</router-link>
        <router-link to="/reports" class="nav-link" active-class="active"><FileText class="w-4 h-4" /> 分析报告</router-link>
        <router-link to="/investigations" class="nav-link" active-class="active"><ShieldAlert class="w-4 h-4" /> 调查中心</router-link>
        <router-link to="/knowledge" class="nav-link" active-class="active"><BookOpen class="w-4 h-4" /> 企业知识库</router-link>
        <router-link to="/evaluations" class="nav-link" active-class="active"><Gauge class="w-4 h-4" /> 评测基线</router-link>
      </nav>
      <div class="p-3 border-t border-slate-200 dark:border-slate-700">
        <router-link to="/import" class="flex items-center gap-2 w-full px-3 py-2 rounded-lg bg-primary/10 text-primary text-sm font-medium hover:bg-primary/20 transition"><Upload class="w-4 h-4" /> 导入 CSV</router-link>
        <div class="mt-2 flex items-center justify-between gap-2 px-1"><span class="text-xs text-slate-400 truncate" v-if="store.workspaceId">{{ store.workspaceId.slice(0,8) }}</span><button class="text-xs text-slate-400 hover:text-slate-700" @click="logout">退出</button></div>
      </div>
    </aside>
    <main class="flex-1 overflow-auto"><router-view /></main>
  </div>
</template>

<script setup>
import { Activity, MessageSquare, BarChart3, FileText, Upload, Gauge, BookOpen, ShieldAlert } from 'lucide-vue-next'
import { watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useWorkspaceStore } from './stores/workspace'
import { clearAccessToken } from './lib/auth'

// 应用启动时只初始化默认 Workspace；各业务页面仍以请求路径中的 workspaceId 做服务端隔离。
const store = useWorkspaceStore()
const route = useRoute()
const router = useRouter()
// 路由从登录页切换到业务页时，组件不会重建，因此需要在这里开始加载可见 Workspace。
// 登录页始终不发起业务 API，避免未登录时产生误导性报错。
watch(() => route.name, (name) => {
  if (name !== 'Login' && !store.workspaceId) store.init()
}, { immediate: true })
function logout() { clearAccessToken(); store.workspaceId = ''; router.replace('/login') }
</script>
