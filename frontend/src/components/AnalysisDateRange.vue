<template>
  <div class="flex flex-wrap items-center gap-x-3 gap-y-2 text-sm">
    <span class="text-slate-500">
      数据覆盖
      <span class="font-mono text-xs text-slate-600 dark:text-slate-300">{{ coverageLabel }}</span>
    </span>
    <span class="hidden sm:inline text-slate-300">|</span>
    <span class="text-slate-500">分析范围</span>
    <input
      v-model="draftFrom"
      type="date"
      class="rounded border border-slate-200 dark:border-slate-600 px-2 py-1 text-sm bg-white dark:bg-slate-800"
      :max="draftTo || coverageEndDate"
    />
    <span class="text-slate-400">至</span>
    <input
      v-model="draftTo"
      type="date"
      class="rounded border border-slate-200 dark:border-slate-600 px-2 py-1 text-sm bg-white dark:bg-slate-800"
      :min="draftFrom || coverageStartDate"
      :max="coverageEndDate"
    />
    <button
      type="button"
      class="rounded-lg bg-primary text-white text-xs px-3 py-1.5 disabled:opacity-50"
      :disabled="!draftFrom || !draftTo"
      @click="apply"
    >应用</button>
    <button
      type="button"
      class="rounded-lg border border-slate-200 dark:border-slate-600 text-xs px-3 py-1.5 hover:bg-slate-50 dark:hover:bg-slate-800"
      :disabled="!coverageStartDate || !coverageEndDate"
      @click="selectAll"
    >全部</button>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { toDateInput } from '../lib/analysis-window'

const props = defineProps({
  coverageStart: { type: String, default: null },
  coverageEnd: { type: String, default: null },
  from: { type: String, default: '' },
  to: { type: String, default: '' }
})

const emit = defineEmits(['apply'])

const draftFrom = ref(props.from)
const draftTo = ref(props.to)

const coverageStartDate = computed(() => toDateInput(props.coverageStart))
const coverageEndDate = computed(() => toDateInput(props.coverageEnd))
const coverageLabel = computed(() => {
  if (!coverageStartDate.value && !coverageEndDate.value) return '—'
  return `${coverageStartDate.value || '-'} ~ ${coverageEndDate.value || '-'}`
})

watch(() => [props.from, props.to], ([from, to]) => {
  draftFrom.value = from
  draftTo.value = to
})

function apply() {
  if (!draftFrom.value || !draftTo.value) return
  emit('apply', { from: draftFrom.value, to: draftTo.value })
}

function selectAll() {
  if (!coverageStartDate.value || !coverageEndDate.value) return
  draftFrom.value = coverageStartDate.value
  draftTo.value = coverageEndDate.value
  emit('apply', { from: draftFrom.value, to: draftTo.value })
}
</script>
