<template>
  <div class="flex h-screen bg-slate-50 dark:bg-slate-900 text-slate-900 dark:text-slate-100 overflow-hidden">
    <aside class="w-64 bg-white dark:bg-slate-800 border-r border-slate-200 dark:border-slate-700 flex flex-col shrink-0">
      <div class="p-5 border-b border-slate-200 dark:border-slate-700">
        <div class="flex items-center gap-3">
          <div class="w-8 h-8 bg-primary rounded-lg flex items-center justify-center"><Activity class="w-5 h-5 text-white" /></div>
          <span class="font-bold text-lg">InsightFlow</span>
        </div>
      </div>
      <nav class="flex-1 p-3 space-y-1">
        <router-link to="/" class="nav-link" active-class="active">
          <LayoutDashboard class="w-5 h-5" /> 仪表盘
        </router-link>
        <router-link to="/issues" class="nav-link" active-class="active">
          <Tags class="w-5 h-5" /> 主题分析
        </router-link>
        <router-link to="/import" class="nav-link" active-class="active">
          <Upload class="w-5 h-5" /> 数据导入
        </router-link>
        <router-link to="/reports" class="nav-link" active-class="active">
          <FileText class="w-5 h-5" /> 分析报告
        </router-link>
      </nav>
      <div class="p-4 border-t border-slate-200 dark:border-slate-700">
        <div class="text-xs text-slate-500">工作区</div>
        <div class="text-xs font-mono mt-1 truncate">{{ store.workspaceId || '初始化中...' }}</div>
      </div>
    </aside>
    <main class="flex-1 overflow-auto">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { Activity, LayoutDashboard, Tags, Upload, FileText } from 'lucide-vue-next'
import { useWorkspaceStore } from './stores/workspace'
const store = useWorkspaceStore()
store.init()
</script>
