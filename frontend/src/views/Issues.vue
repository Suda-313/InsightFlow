<template>
  <div class="p-6">
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-2xl font-bold">主题分析</h1>
      <input v-model="search" placeholder="搜索主题..." class="bg-slate-100 dark:bg-slate-700 rounded-lg px-3 py-1.5 text-sm border-0 outline-none w-48">
    </div>
    <div v-if="loading" class="grid grid-cols-3 gap-4"><div v-for="i in 6" :key="i" class="card p-4 animate-pulse"><div class="h-4 bg-slate-200 dark:bg-slate-700 rounded w-24 mb-2"></div><div class="h-6 bg-slate-200 dark:bg-slate-700 rounded w-12"></div></div></div>
    <div v-else-if="!filtered.length" class="card p-12 text-center"><Tags class="w-12 h-12 mx-auto text-slate-300 mb-3" /><p class="text-slate-500">暂无主题数据</p></div>
    <div v-else class="grid grid-cols-3 gap-4">
      <router-link v-for="i in filtered" :key="i.canonicalKey" :to="'/issues/' + i.canonicalKey" class="card p-4 hover:shadow-md transition no-underline text-inherit">
        <div class="font-semibold">{{ i.canonicalName }}</div>
        <div class="text-xs text-slate-500 mt-1">{{ i.canonicalKey }}</div>
        <div class="text-2xl font-bold mt-3">{{ i.feedbackCount }}</div>
        <div class="text-xs text-slate-400">条反馈</div>
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