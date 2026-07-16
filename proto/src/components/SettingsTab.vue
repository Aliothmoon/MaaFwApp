<script setup>
import { useInterface } from '../composables/useInterface.js'

const { state, labelOf, availableResources, selectedController, toggleLang } = useInterface()
</script>

<template>
  <div class="responsive-content p-4 space-y-3">

    <!-- ═══ Resource ═══ -->
    <section class="surface-card px-4 py-3.5 space-y-2.5">
      <p class="section-label">资源</p>
      <select v-model="state.selectedResourceName" class="ctrl-select" :disabled="state.isRunning">
        <option v-for="r in availableResources" :key="r.name" :value="r.name">{{ labelOf(r) }}</option>
      </select>
      <p class="text-[12px] leading-relaxed text-[var(--muted)]">切换资源会影响任务适用性，运行期间不可切换</p>
      <div class="flex justify-between text-[14px] pt-1 border-t border-[var(--black-05)]">
        <span class="text-[var(--muted)]">控制器</span>
        <span class="text-[var(--ink)]">{{ labelOf(selectedController) || 'Android' }}</span>
      </div>
    </section>

    <!-- ═══ Appearance ═══ -->
    <section class="surface-card px-4 py-3.5 space-y-2.5">
      <p class="section-label">外观与语言</p>
      <div class="flex items-center justify-between">
        <span class="text-[14px] text-[var(--ink)]">界面语言</span>
        <button @click="toggleLang" class="btn-ghost min-h-11 px-4 rounded-full text-xs">
          {{ state.lang === 'zh_cn' ? '简体中文' : 'English' }}
        </button>
      </div>
    </section>

    <!-- ═══ About ═══ -->
    <section class="surface-card px-4 py-3.5">
      <p class="section-label">关于</p>
      <div class="mt-2 space-y-1.5 text-[14px]">
        <div class="flex justify-between">
          <span class="text-[var(--muted)]">版本</span>
          <span class="text-[var(--ink)] font-mono">{{ state.interface.version ? `v${state.interface.version}` : '内置示例' }}</span>
        </div>
        <div class="flex justify-between">
          <span class="text-[var(--muted)]">协议</span>
          <span class="text-[var(--ink)]">ProjectInterface V2</span>
        </div>
      </div>
    </section>

  </div>
</template>
