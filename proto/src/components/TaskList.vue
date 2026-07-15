<script setup>
import { useInterface, asList } from '../composables/useInterface.js'

const { state, labelOf, visibleTasks, optCount } = useInterface()

function openDrawer(task) {
  if (state.isRunning) return
  if (state.expandedTaskName === task.name) {
    state.expandedTaskName = null
  } else {
    state.expandedTaskName = task.name
    state.selectedTaskName = task.name
  }
}

// Design.md uses one interaction accent throughout the product.
const ACCENT = { text: 'var(--primary)', bg: 'var(--primary-soft)', bar: 'var(--primary)' }

function groupsOf(task) {
  return asList(task.group)
}
</script>

<template>
  <div class="px-4 pt-3 pb-4 space-y-2">
    <h2 class="section-label px-1 mb-2">任务列表</h2>

    <div class="task-list-grid">
      <div
        v-for="{ task, supported } in visibleTasks"
        :key="task.name"
        class="surface-card relative overflow-hidden flex items-center gap-3 pl-4 pr-3.5 py-3.5 transition-all duration-200"
        :class="[
          !supported ? 'opacity-30' : 'cursor-pointer active:bg-[var(--pearl)]',
          state.expandedTaskName === task.name ? '!border-[var(--primary-focus)]' : '',
        ]"
        @click="supported && optCount(task) > 0 && openDrawer(task)"
      >
      <!-- 左侧分组色条 -->
      <span
        class="absolute left-0 top-2.5 bottom-2.5 w-[3px] rounded-r-full"
        :style="{ background: groupsOf(task).length ? ACCENT.bar : 'var(--chip-gray)' }"
      ></span>

      <label class="w-11 h-11 -m-2.5 flex items-center justify-center flex-shrink-0" @click.stop>
        <input type="checkbox" class="cb" :checked="state.taskChecks[task.name]" :disabled="!supported || state.isRunning"
          @change="state.taskChecks[task.name] = $event.target.checked" />
      </label>

      <div class="flex-1 min-w-0">
        <div class="flex items-center gap-1.5 min-w-0">
          <span class="text-[15px] tracking-tight truncate"
            :class="state.expandedTaskName === task.name ? 'text-[var(--primary)] font-semibold font-display' : 'text-[var(--ink)]'"
          >{{ labelOf(task) }}</span>
        </div>
        <div class="mt-1.5 flex items-center gap-1.5 flex-wrap">
          <span
            v-for="g in groupsOf(task)"
            :key="g"
            class="badge"
            :style="{ color: ACCENT.text, background: ACCENT.bg }"
          >{{ g }}</span>
          <span v-if="optCount(task) > 0" class="text-[11px] text-[var(--muted)]">
            {{ optCount(task) }} 项选项
          </span>
          <span v-if="!supported" class="badge badge-tone-error-flat">当前环境不可用</span>
        </div>
      </div>

        <svg v-if="supported && optCount(task) > 0"
          class="w-4 h-4 text-[var(--muted)] flex-shrink-0"
          fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"
        >
          <path stroke-linecap="round" stroke-linejoin="round" d="M9 5l7 7-7 7" />
        </svg>
      </div>
    </div>
  </div>
</template>
