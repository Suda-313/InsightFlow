<template>
  <div class="flex h-screen bg-slate-50 dark:bg-slate-900 text-slate-900 dark:text-slate-100 overflow-hidden">
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
      </nav>
      <div class="p-3 border-t border-slate-200 dark:border-slate-700">
        <router-link to="/?import=1" class="flex items-center gap-2 w-full px-3 py-2 rounded-lg bg-primary/10 text-primary text-sm font-medium hover:bg-primary/20 transition"><Upload class="w-4 h-4" /> 导入 CSV</router-link>
        <div class="text-xs text-slate-400 mt-2 px-1 truncate" v-if="store.workspaceId">{{ store.workspaceId.slice(0,8) }}</div>
      </div>
    </aside>
    <main class="flex-1 overflow-auto">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { Activity, MessageSquare, BarChart3, FileText, Upload } from 'lucide-vue-next'
import { useWorkspaceStore } from './stores/workspace'
const store = useWorkspaceStore()
store.init()
</script>