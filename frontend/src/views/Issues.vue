<template>
  <div class="p-6">
    <h1 class="text-2xl font-bold mb-6">主题分析</h1>
    <div class="grid grid-cols-2 md:grid-cols-3 gap-4">
      <router-link v-for="i in issues" :key="i.canonicalKey" :to="'/issues/' + i.canonicalKey" class="card p-4 hover:shadow-md transition cursor-pointer no-underline text-inherit">
        <div class="font-semibold">{{ i.canonicalName }}</div>
        <div class="text-xs text-slate-500 mt-1">{{ i.canonicalKey }}</div>
        <div class="text-2xl font-bold mt-2">{{ i.feedbackCount }}</div>
        <div class="text-xs text-slate-400">条反馈</div>
      </router-link>
    </div>
    <div v-if="!issues.length" class="text-slate-400 text-center mt-20">暂无数据</div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useWorkspaceStore } from '../stores/workspace'
const store = useWorkspaceStore()
const issues = ref([])

async function load() {
  if (!store.workspaceId) return
  let r = await fetch('/api/v1/workspaces/' + store.workspaceId + '/issues')
  let d = await r.json()
  issues.value = Array.isArray(d) ? d : []
}

onMounted(load)
watch(() => store.workspaceId, load)
</script>
