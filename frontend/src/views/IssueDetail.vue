<template>
  <div class="p-6">
    <router-link to="/issues" class="text-sm text-primary hover:underline mb-4 inline-block">&larr; 返回主题列表</router-link>
    <h1 class="text-2xl font-bold mt-2">{{ key }}</h1>
    <div class="text-sm text-slate-500 mt-4">该主题详情 API 待后端完善</div>
  </div>
</template>
<script setup>
import { useRoute } from 'vue-router'
const key = useRoute().params.key
</script>
