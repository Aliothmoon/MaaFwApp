<script setup>
import { useInterface } from '../composables/useInterface.js'
const { state, pipelineJson, pipelinePreview, highlightJson, selectedTask } = useInterface()
</script>

<template>
  <details class="mt-2 rounded-md border border-[var(--hairline)] bg-[var(--pearl)] overflow-hidden">
    <summary class="flex items-center gap-2 px-3 py-2 cursor-pointer">
      <span class="w-1 h-3.5 rounded-full bg-[var(--primary)] flex-shrink-0" />
      <span class="text-[11px] font-semibold text-[var(--ink-soft)]">Pipeline Override</span>
      <span class="text-[9px] text-[var(--muted)] truncate flex-1 font-mono">{{ selectedTask?.name }}</span>
      <svg class="w-3.5 h-3.5 text-[var(--muted)] transition-transform duration-200 details-arrow" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
        <path stroke-linecap="round" stroke-linejoin="round" d="M19 9l-7 7-7-7" />
      </svg>
    </summary>
    <div class="px-3 pb-3">
      <div class="flex items-center gap-1 text-[9px] text-[var(--muted)] mb-2 flex-wrap font-mono">
        <span>merge:</span>
        <span class="text-[var(--primary)]">global</span><span>→</span>
        <span class="text-[var(--primary)]">resource</span><span>→</span>
        <span class="text-[var(--primary)]">controller</span><span>→</span>
        <span class="text-[var(--primary)]">task</span>
      </div>
      <pre v-if="Object.keys(pipelinePreview).length > 0"
        class="text-[10px] font-mono leading-relaxed overflow-x-auto rounded-sm p-2.5 border border-[var(--black-10)]"
        style="background:var(--tile-dark);color:white"
        v-html="highlightJson(pipelineJson)" />
      <div v-else class="py-3 text-center text-[11px] text-[var(--muted)] italic">No pipeline overrides.</div>
    </div>
  </details>
</template>

<style scoped>
details[open] .details-arrow { transform: rotate(180deg); }
</style>
