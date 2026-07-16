<script setup>
import { computed } from 'vue'
import { useInterface, asList, isOptionApplicable } from '../composables/useInterface.js'
import OptionRenderer from './OptionRenderer.vue'
import PipelinePreview from './PipelinePreview.vue'
import BottomSheet from './BottomSheet.vue'

const { state, labelOf, descOf } = useInterface()

const isOpen = computed(() => state.expandedTaskName !== null)
const task = computed(() => state.interface.task?.find((t) => t.name === state.expandedTaskName))
const taskOptions = computed(() => task.value ? asList(task.value.option).filter((n) => isOptionApplicable(n)) : [])

function close() { state.expandedTaskName = null }
</script>

<template>
  <BottomSheet
    v-if="task"
    :open="isOpen"
    :title="labelOf(task)"
    aria-label="任务选项"
    @close="close"
  >
    <template #meta>
      <div class="flex items-center gap-2 mt-0.5">
        <span class="text-[10px] font-mono px-1.5 py-0.5 rounded-xs bg-[var(--black-05)] text-[var(--muted)]">entry: {{ task.entry }}</span>
        <span v-if="taskOptions.length" class="text-[10px] text-[var(--muted)]">{{ taskOptions.length }} 项选项</span>
      </div>
    </template>

    <template v-if="descOf(task)" #intro>
      <div class="px-5 py-3 text-[12px] text-[var(--muted)] leading-relaxed border-b border-[var(--divider)] flex-shrink-0">
        {{ descOf(task) }}
      </div>
    </template>

    <div class="px-4 py-3 space-y-2">
      <div v-if="taskOptions.length > 0" class="space-y-2">
        <OptionRenderer v-for="name in taskOptions" :key="name" :option-name="name" source="task" />
      </div>
      <div v-else class="py-6 text-center text-sm text-[var(--muted)]">
        此任务没有可配置选项
      </div>
      <PipelinePreview />
    </div>
  </BottomSheet>
</template>
