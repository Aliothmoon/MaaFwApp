<script setup>
import { computed, ref, watch } from 'vue'
import { asList, useInterface } from '../composables/useInterface.js'
import BottomSheet from './BottomSheet.vue'

const {
  state, taskCatalog, labelOf, addTasks, isTaskSupported,
} = useInterface()

const selectedNames = ref([])
const queuedNames = computed(() => new Set(state.taskQueue.map((task) => task.name)))
const catalogGroups = computed(() => {
  const groups = new Map()
  for (const task of taskCatalog.value) {
    const taskGroups = asList(task.group)
    for (const group of taskGroups.length > 0 ? taskGroups : ['未分组']) {
      if (!groups.has(group)) groups.set(group, [])
      groups.get(group).push(task)
    }
  }
  return [...groups].map(([name, tasks]) => ({ name, tasks }))
})

watch(() => state.showTaskCatalog, (isOpen) => {
  if (!isOpen) selectedNames.value = []
})

function toggleTask(taskName, checked) {
  if (checked) {
    if (!selectedNames.value.includes(taskName)) selectedNames.value.push(taskName)
  } else {
    selectedNames.value = selectedNames.value.filter((name) => name !== taskName)
  }
}

function close() {
  state.showTaskCatalog = false
}

function confirm() {
  addTasks(selectedNames.value)
  selectedNames.value = []
}
</script>

<template>
  <BottomSheet
    :open="state.showTaskCatalog"
    title="添加任务"
    subtitle="按选择顺序追加到当前配置末尾"
    height="72%"
    aria-label="添加任务"
    @close="close"
  >
    <div class="px-4 py-3">
        <section v-for="group in catalogGroups" :key="group.name" class="mb-4">
          <h4 class="section-label px-1 mb-2">{{ group.name }}</h4>
          <div class="space-y-1.5">
            <label
              v-for="task in group.tasks"
              :key="`${group.name}_${task.name}`"
              class="surface-option min-h-11 px-3 py-2.5 flex items-center gap-3"
              :class="queuedNames.has(task.name) ? 'opacity-55' : ''"
            >
              <input
                type="checkbox"
                class="cb"
                :checked="queuedNames.has(task.name) || selectedNames.includes(task.name)"
                :disabled="queuedNames.has(task.name)"
                @change="toggleTask(task.name, $event.target.checked)"
              />
              <span class="flex-1 min-w-0 text-[14px] text-[var(--ink)]">{{ labelOf(task) }}</span>
              <span v-if="queuedNames.has(task.name)" class="text-[11px] text-[var(--muted)]">已添加</span>
              <span v-else-if="!isTaskSupported(task)" class="text-[11px] text-[var(--muted)]">当前资源不可用</span>
            </label>
          </div>
        </section>
      </div>

    <template #footer>
      <div class="px-4 py-3 border-t border-[var(--black-06)] flex-shrink-0">
        <button
          type="button"
          class="task-action-button btn-primary w-full min-h-11 px-5 text-[15px]"
          :disabled="selectedNames.length === 0"
          @click="confirm"
        >
          确认添加<span v-if="selectedNames.length">（{{ selectedNames.length }}）</span>
        </button>
      </div>
    </template>
  </BottomSheet>
</template>
