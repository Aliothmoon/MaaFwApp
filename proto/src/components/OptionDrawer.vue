<script setup>
import { computed } from 'vue'
import { useInterface, asList, isOptionApplicable } from '../composables/useInterface.js'
import OptionRenderer from './OptionRenderer.vue'
import PipelinePreview from './PipelinePreview.vue'

const { state, labelOf, descOf } = useInterface()

const isOpen = computed(() => state.expandedTaskName !== null)
const task = computed(() => state.interface.task?.find((t) => t.name === state.expandedTaskName))
const taskOptions = computed(() => task.value ? asList(task.value.option).filter((n) => isOptionApplicable(n)) : [])

function close() { state.expandedTaskName = null }
</script>

<template>
  <!-- Backdrop -->
  <transition name="fade">
    <div v-if="isOpen" class="absolute inset-0 bg-[var(--black-20)] z-30" @click="close" />
  </transition>

  <!-- Sheet -->
  <transition name="sheet">
    <div v-if="isOpen && task"
      class="absolute bottom-0 left-0 right-0 z-40 bg-[var(--canvas)] rounded-t-[18px] border-t border-[var(--hairline)] flex flex-col"
      style="max-height: 78%;"
    >
      <!-- Drag handle -->
      <div class="flex justify-center pt-2.5 pb-1 flex-shrink-0">
        <div class="w-9 h-1 rounded-full bg-[var(--chip-gray)]" />
      </div>

      <!-- Header -->
      <div class="px-5 py-2.5 flex items-start gap-3 border-b border-[var(--black-06)] flex-shrink-0">
        <div class="flex-1 min-w-0">
          <h3 class="text-[17px] font-semibold font-display tracking-tight text-[var(--ink)]">{{ labelOf(task) }}</h3>
          <div class="flex items-center gap-2 mt-0.5">
            <span class="text-[10px] font-mono px-1.5 py-0.5 rounded-[5px] bg-[var(--black-05)] text-[var(--muted)]">entry: {{ task.entry }}</span>
            <span v-if="taskOptions.length" class="text-[10px] text-[var(--muted)]">{{ taskOptions.length }} options</span>
          </div>
        </div>
        <button @click="close" class="w-11 h-11 rounded-full bg-[color:var(--chip-gray)]/60 flex items-center justify-center flex-shrink-0 active:scale-95 transition-transform">
          <svg class="w-4 h-4 text-[var(--ink)]" fill="none" stroke="currentColor" stroke-width="2.5" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12"/>
          </svg>
        </button>
      </div>

      <!-- Description -->
      <div v-if="descOf(task)" class="px-5 py-3 text-[12px] text-[var(--muted)] leading-relaxed border-b border-[var(--divider)] flex-shrink-0">
        {{ descOf(task) }}
      </div>

      <!-- Scrollable content -->
      <div class="overflow-y-auto px-4 py-3 space-y-2">
        <div v-if="taskOptions.length > 0" class="space-y-2">
          <OptionRenderer v-for="name in taskOptions" :key="name" :option-name="name" source="task" />
        </div>
        <div v-else class="py-6 text-center text-sm text-[var(--muted)]">
          This task has no configurable options.
        </div>
        <PipelinePreview />
      </div>
    </div>
  </transition>
</template>
