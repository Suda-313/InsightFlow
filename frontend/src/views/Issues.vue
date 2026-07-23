<template>
  <div class="p-6">
    <div class="flex items-center justify-between mb-6">
      <div><h1 class="text-2xl font-bold">主题分析</h1><p class="text-sm text-slate-500 mt-1">共 {{ issues.length }} 个主题</p></div>
      <input v-model="search" placeholder="搜索主题..." class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-lg px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-primary/20 w-56">
    </div>
    <div v-if="loading" class="grid grid-cols-3 gap-4"><div v-for="i in 6" :key="i" class="card p-5 animate-pulse"><div class="h-4 bg-slate-200 dark:bg-slate-700 rounded w-24 mb-3"></div><div class="h-8 bg-slate-200 dark:bg-slate-700 rounded w-12"></div></div></div>
    <div v-else-if="!filtered.length" class="text-center py-20"><Tags class="w-12 h-12 mx-auto text-slate-300 mb-3" /><p class="text-slate-500">暂无主题数据</p></div>
    <div v-else class="grid grid-cols-3 gap-4">
      <router-link v-for="i in filtered" :key="i.canonicalKey" :to="'/issues/' + i.canonicalKey" class="card p-5 hover:shadow-lg hover:-translate-y-0.5 transition-all duration-200 no-underline text-inherit">
        <div class="flex items-start justify-between mb-2">
          <div class="w-10 h-10 rounded-xl bg-gradient-to-br from-blue-100 to-blue-200 dark:from-blue-900 dark:to-blue-800 flex items-center justify-center"><Tags class="w-5 h-5 text-blue-600 dark:text-blue-400" /></div>
          <span class="text-xs text-slate-400">#{{ i.feedbackCount }}</span>
        </div>
        <div class="font-semibold text-lg mb-1">{{ i.canonicalName }}</div>
        <div class="text-xs text-slate-400 font-mono">{{ i.canonicalKey }}</div>
        <div class="mt-3 w-full bg-slate-100 dark:bg-slate-700 rounded-full h-1.5"><div class="bg-blue-500 h-1.5 rounded-full" :style="{ width: Math.min(100, (i.feedbackCount / maxCount) * 100) + '%' }"></div></div>
      </router-link>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { Tags } from 'lucide-vue-next'
import { useWorkspaceStore } from '../stores/workspace'
const store = useWorkspaceStore()
const issues = ref([]), loading = ref(true), search = ref('')
const filtered = computed(() => issues.value.filter(i => i.canonicalName?.includes(search.value) || i.canonicalKey?.includes(search.value)))
const maxCount = computed(() => Math.max(1, ...issues.value.map(i => i.feedbackCount)))

async function load() {
  if (!store.workspaceId) return; loading.value = true
  let r = await fetch('/api/v1/workspaces/' + store.workspaceId + '/issues')
  let d = await r.json()
  issues.value = Array.isArray(d) ? d : []
  loading.value = false
}
onMounted(load)
watch(() => store.workspaceId, load)
</script>