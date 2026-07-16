<script setup>
import { nextTick, ref } from 'vue'
import { useInterface, asList } from '../composables/useInterface.js'

defineProps({
  showHeading: { type: Boolean, default: true },
})

const { state, labelOf, visibleTasks, optCount, removeTask } = useInterface()
const pendingDeleteTaskName = ref(null)
const taskListRef = ref(null)
const taskListHeadingRef = ref(null)

function openDrawer(task) {
  if (state.isRunning) return
  state.showTaskCatalog = false
  if (state.expandedTaskName === task.name) {
    state.expandedTaskName = null
  } else {
    state.expandedTaskName = task.name
    state.selectedTaskName = task.name
  }
}

function openTaskCatalog() {
  if (state.isRunning) return
  state.expandedTaskName = null
  state.showTaskCatalog = true
}

// Design.md uses one interaction accent throughout the product.
const ACCENT = { text: 'var(--primary)', bg: 'var(--primary-soft)', bar: 'var(--primary)' }

function groupsOf(task) {
  return asList(task.group)
}

function handleDelete(taskName) {
  if (pendingDeleteTaskName.value === taskName) {
    const removedIndex = visibleTasks.value.findIndex(({ task }) => task.name === taskName)
    const removed = removeTask(taskName)
    pendingDeleteTaskName.value = null
    if (removed) {
      nextTick(() => {
        const buttons = taskListRef.value?.querySelectorAll('[data-task-delete]') || []
        const nextButton = buttons[Math.min(removedIndex, buttons.length - 1)]
        if (nextButton) nextButton.focus()
        else taskListHeadingRef.value?.focus()
      })
    }
    return
  }
  pendingDeleteTaskName.value = taskName
}
</script>

<template>
  <div class="px-4 pt-3 pb-4 space-y-2">
    <div class="flex items-center justify-between gap-3 mb-2">
      <h2
        ref="taskListHeadingRef"
        class="section-label px-1"
        :class="{ 'sr-only': !showHeading }"
        tabindex="-1"
      >任务列表</h2>
      <button
        type="button"
        class="task-list-add-button"
        :disabled="state.isRunning"
        @click="openTaskCatalog"
      >
        <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
          <path stroke-linecap="round" d="M12 5v14M5 12h14" />
        </svg>
        添加任务
      </button>
    </div>

    <div ref="taskListRef" class="task-list-grid">
      <div
        v-for="{ task, configured, supported } in visibleTasks"
        :key="task.name"
        class="surface-card relative overflow-hidden flex items-center gap-3 pl-4 pr-3.5 py-3.5 transition-all duration-200"
        :class="[
          !supported ? 'cursor-default' : 'cursor-pointer active:bg-[var(--pearl)]',
          state.expandedTaskName === task.name ? '!border-[var(--primary-focus)]' : '',
        ]"
        @click="supported && optCount(task) > 0 && openDrawer(task)"
      >
      <!-- 左侧分组色条 -->
      <span
        class="absolute left-0 top-2.5 bottom-2.5 w-[3px] rounded-r-full"
        :class="!supported ? 'opacity-30' : ''"
        :style="{ background: groupsOf(task).length ? ACCENT.bar : 'var(--chip-gray)' }"
      ></span>

      <label class="w-11 h-11 -m-2.5 flex items-center justify-center flex-shrink-0" @click.stop>
        <input type="checkbox" class="cb" :checked="configured.enabled" :disabled="!supported || state.isRunning"
          @change="configured.enabled = $event.target.checked" />
      </label>

      <div class="flex-1 min-w-0" :class="!supported ? 'opacity-30' : ''">
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

        <div class="flex items-center flex-shrink-0 -mr-1">
          <svg v-if="supported && optCount(task) > 0"
            class="w-4 h-4 text-[var(--muted)]"
            fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"
          >
            <path stroke-linecap="round" stroke-linejoin="round" d="M9 5l7 7-7 7" />
          </svg>
          <button
            type="button"
            data-task-delete
            class="task-delete-button"
            :class="pendingDeleteTaskName === task.name ? 'pending' : ''"
            :disabled="state.isRunning"
            :aria-label="pendingDeleteTaskName === task.name ? `确认删除任务：${labelOf(task)}` : `删除任务：${labelOf(task)}`"
            :title="pendingDeleteTaskName === task.name ? '再次点击确认删除' : '删除任务'"
            @blur="pendingDeleteTaskName = null"
            @click.stop="handleDelete(task.name)"
          >
            <span v-if="pendingDeleteTaskName === task.name">确认</span>
            <svg v-else class="w-[17px] h-[17px]" fill="none" stroke="currentColor" stroke-width="1.8" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" d="M4 7h16M9 7V4h6v3m-9 0 1 13h10l1-13M10 11v5m4-5v5" />
            </svg>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
