<script setup>
import { computed } from 'vue'
import { useInterface } from '../composables/useInterface.js'

const { state, t, labelOf, checkedTaskCount, availableResources } = useInterface()

const phase = computed(() => {
  if (state.isRunning) return { key: 'running', text: '正在运行', cls: 'text-[var(--primary-on-dark)]', dot: 'dot-running' }
  return { key: 'idle', text: '准备就绪', cls: 'text-[var(--canvas)]', dot: 'dot-idle' }
})

const progressPct = computed(() => {
  const p = state.runProgress
  if (!p || p.total === 0) return 0
  return Math.round((p.current / p.total) * 100)
})

const resultBadge = computed(() => {
  const r = state.lastResult
  if (!r) return null
  return {
    success: { cls: 'badge-tone-success', text: r.message },
    warn: { cls: 'badge-tone-warning', text: r.message },
    error: { cls: 'badge-tone-error', text: r.message },
  }[r.level]
})

const totalTasks = computed(() => (state.interface.task || []).length)
</script>

<template>
  <div>
    <!-- Dark product tile: the runner is the product on this surface. -->
    <section class="hero-card">
      <div class="flex items-center gap-2">
        <span class="status-dot" :class="phase.dot" />
        <span class="hero-eyebrow">Runner 状态</span>
        <span v-if="state.runProgress" class="ml-auto text-xs text-[var(--body-muted)]">
          {{ state.runProgress.current }} / {{ state.runProgress.total }}
        </span>
        <span v-else-if="resultBadge" class="ml-auto badge-tone" :class="resultBadge.cls">{{ resultBadge.text }}</span>
      </div>

      <div>
        <h1 class="hero-title" :class="phase.cls">{{ phase.text }}</h1>
        <template v-if="state.runProgress">
          <p class="mt-2 text-[14px] text-[var(--body-muted)] truncate">{{ state.runProgress.taskName }}</p>
          <div class="progress-track mt-5">
            <div class="progress-fill" :style="{ width: progressPct + '%' }" />
          </div>
        </template>
        <button v-else class="mt-4 min-h-11 text-[14px] text-[var(--primary-on-dark)]" @click="state.activeTab = 'tasks'">
          前往任务列表 →
        </button>
      </div>
    </section>

    <section class="px-4 py-8 bg-[var(--parchment)] space-y-3">
      <h2 class="text-[28px] leading-tight font-display text-[var(--ink)]">今天要运行什么？</h2>
      <p class="text-[14px] leading-relaxed text-[var(--muted)]">检查当前配置，然后开始一组自动化任务。</p>

      <button class="surface-card w-full mt-6 px-5 py-5 text-left" @click="state.activeTab = 'tasks'">
        <div class="flex items-center justify-between gap-4">
          <div class="min-w-0">
            <p class="section-label">当前配置</p>
            <p class="mt-2 text-[17px] font-semibold text-[var(--ink)] truncate">
              {{ state.activePresetName || '自定义选择' }}
            </p>
          </div>
          <div class="flex items-center gap-2 flex-shrink-0">
            <span class="badge-tone badge-tone-info">{{ checkedTaskCount }} / {{ totalTasks }}</span>
            <svg class="w-4 h-4 text-[var(--primary)]" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" d="M9 5l7 7-7 7"/>
            </svg>
          </div>
        </div>
      </button>
    </section>

    <section class="px-4 py-8 bg-[var(--canvas)] space-y-3">
      <h2 class="section-label px-1">项目</h2>
      <div class="surface-card px-5 py-4">
        <div class="space-y-3 text-[14px]">
          <div class="flex justify-between gap-4">
            <span class="text-[var(--muted)]">名称</span>
            <span class="text-[var(--ink)] font-semibold text-right">{{ t(state.interface.label || state.interface.name) }}</span>
          </div>
          <div class="flex justify-between">
            <span class="text-[var(--muted)]">任务</span>
            <span class="text-[var(--ink)]">{{ totalTasks }} 个</span>
          </div>
          <div class="flex justify-between items-center gap-4 pt-3 border-t border-[var(--divider)]">
            <span class="text-[var(--muted)]">资源</span>
            <select v-model="state.selectedResourceName" class="ctrl-select-compact" :disabled="state.isRunning">
              <option v-for="r in availableResources" :key="r.name" :value="r.name">{{ labelOf(r) }}</option>
            </select>
          </div>
        </div>
      </div>
    </section>

    <section class="px-4 pt-2 pb-10 bg-[var(--canvas)]">
      <h2 class="section-label px-1 mb-2">日志</h2>
      <div class="max-h-40 overflow-y-auto surface-card p-3">
        <div v-for="(log, i) in state.logEntries" :key="i" class="font-mono text-[11px] leading-relaxed flex gap-2 px-1 py-0.5">
          <span class="text-[var(--muted)] flex-shrink-0">{{ log.time }}</span>
          <span :class="log.level === 'info' || log.level === 'success' ? 'text-[var(--primary)]' : 'text-[var(--ink-soft)]'">{{ log.message }}</span>
        </div>
        <div v-if="state.logEntries.length === 0" class="text-[var(--muted)] text-[11px] px-1 py-1">暂无日志</div>
      </div>
    </section>
  </div>
</template>
