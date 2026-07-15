<script setup>
import { computed, ref } from 'vue'
import { useInterface } from './composables/useInterface.js'
import HomeTab from './components/HomeTab.vue'
import SettingsTab from './components/SettingsTab.vue'
import TaskList from './components/TaskList.vue'
import LivePreview from './components/LivePreview.vue'
import OptionRenderer from './components/OptionRenderer.vue'
import OptionDrawer from './components/OptionDrawer.vue'

const {
  state, labelOf,
  globalOptionNames, resourceOptionNames, controllerOptionNames,
  selectedController, selectedResource, checkedTaskCount,
  applyPreset, toggleStart,
} = useInterface()

function startTasks() {
  if (!state.isRunning) toggleStart()
}

function stopTasks() {
  if (state.isRunning) toggleStart()
}

const TABS = [
  { key: 'home', label: '首页' },
  { key: 'tasks', label: '任务' },
  { key: 'settings', label: '设置' },
]

const activeTabLabel = computed(() => TABS.find((tab) => tab.key === state.activeTab)?.label || '')
const navMenuOpen = ref(false)

function navigateTo(tab) {
  state.activeTab = tab
  navMenuOpen.value = false
}
</script>

<template>
  <div class="h-screen flex flex-col app-bg text-[var(--ink)] font-sans select-none">
    <div class="app-shell flex-1 flex flex-col w-full mx-auto overflow-hidden relative">

      <!-- Design.md global navigation: true black, 44px, minimal chrome. -->
      <header class="global-nav flex-shrink-0">
        <div class="global-nav__brand">
          <span class="global-nav__mark">M</span>
          <span class="truncate">MaaFramework</span>
        </div>
        <nav class="global-nav__links" aria-label="主要导航">
          <button v-for="tab in TABS" :key="'top-' + tab.key" @click="navigateTo(tab.key)">{{ tab.label }}</button>
        </nav>
        <span class="global-nav__status">ProjectInterface V2</span>
        <div class="global-nav__actions">
          <button class="global-nav__icon-button global-nav__search" aria-label="搜索任务" @click="navigateTo('tasks')">
            <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="1.8" viewBox="0 0 24 24">
              <circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>
            </svg>
          </button>
          <button class="global-nav__icon-button global-nav__menu-button" aria-label="打开导航菜单" @click="navMenuOpen = !navMenuOpen">
            <svg class="w-5 h-5" fill="none" stroke="currentColor" stroke-width="1.8" viewBox="0 0 24 24">
              <path d="M4 7h16M4 12h16M4 17h16"/>
            </svg>
          </button>
          <button class="global-nav__icon-button global-nav__task-bag" aria-label="查看已选任务" @click="navigateTo('tasks')">
            <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="1.8" viewBox="0 0 24 24">
              <path d="M6 7h12l1 13H5L6 7Z"/><path d="M9 8V6a3 3 0 0 1 6 0v2"/>
            </svg>
            <span>{{ checkedTaskCount }}</span>
          </button>
        </div>
      </header>

      <nav v-if="navMenuOpen" class="global-nav-menu" aria-label="移动端导航">
        <button v-for="tab in TABS" :key="'menu-' + tab.key" @click="navigateTo(tab.key)">{{ tab.label }}</button>
      </nav>

      <div class="sub-nav-frosted flex-shrink-0">
        <span class="sub-nav-frosted__title">{{ activeTabLabel }}</span>
        <button v-if="state.activeTab !== 'tasks'" class="sub-nav-frosted__action" @click="state.activeTab = 'tasks'">
          配置任务
        </button>
        <span v-else class="sub-nav-frosted__meta">已选择 {{ checkedTaskCount }} 项</span>
      </div>

      <!-- ═══ Scrollable Content ═══ -->
      <div class="flex-1 overflow-y-auto">

        <!-- ── Home ── -->
        <HomeTab v-if="state.activeTab === 'home'" />

        <!-- ── Tasks ── -->
        <template v-else-if="state.activeTab === 'tasks'">
          <!-- 实时预览 -->
          <LivePreview />

          <!-- Presets（配置选择器） -->
          <section v-if="state.interface.preset?.length" class="responsive-content px-4 pt-2">
            <h2 class="section-label px-1 mb-2">配置</h2>
            <div class="flex gap-2 overflow-x-auto pb-1 no-scrollbar">
              <button
                v-for="preset in state.interface.preset"
                :key="preset.name"
                @click="!state.isRunning && applyPreset(preset)"
                class="flex-shrink-0 px-4 py-2.5 chip text-[13px] whitespace-nowrap"
                :class="[
                  state.activePresetName === preset.name ? 'on' : '',
                  state.isRunning ? 'opacity-50' : '',
                ]"
              >{{ labelOf(preset) }}</button>
            </div>
          </section>

          <TaskList />

          <!-- Global Settings -->
          <section v-if="globalOptionNames.length + resourceOptionNames.length + controllerOptionNames.length > 0" class="responsive-content px-4 pb-4">
            <button
              @click="state.showGlobalSettings = !state.showGlobalSettings"
              class="w-full flex items-center justify-between px-4 py-3.5 surface-card text-sm text-[var(--ink-soft)]"
            >
              <span class="flex items-center gap-2">
                <svg class="w-4 h-4 text-[var(--primary)]" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"/>
                  <path stroke-linecap="round" stroke-linejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"/>
                </svg>
                <span class="font-semibold text-[14px]">全局设置</span>
                <span class="text-[11px] text-[var(--muted)]">{{ globalOptionNames.length + resourceOptionNames.length + controllerOptionNames.length }}</span>
              </span>
              <svg class="w-4 h-4 transition-transform duration-200 text-[var(--muted)]" :class="state.showGlobalSettings ? 'rotate-180' : ''" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" d="M19 9l-7 7-7-7"/>
              </svg>
            </button>

            <transition name="expand">
              <div v-show="state.showGlobalSettings" class="mt-2 space-y-2">
                <div v-if="globalOptionNames.length > 0" class="space-y-2">
                  <p class="text-[10px] font-semibold text-[var(--primary)] uppercase tracking-wider px-1 font-display">Global</p>
                  <OptionRenderer v-for="name in globalOptionNames" :key="'g_' + name" :option-name="name" source="global" />
                </div>
                <div v-if="resourceOptionNames.length > 0" class="space-y-2">
                  <p class="text-[10px] font-semibold text-[var(--primary)] uppercase tracking-wider px-1 mt-3 font-display">Resource · {{ labelOf(selectedResource) }}</p>
                  <OptionRenderer v-for="name in resourceOptionNames" :key="'r_' + name" :option-name="name" source="res" />
                </div>
                <div v-if="controllerOptionNames.length > 0" class="space-y-2">
                  <p class="text-[10px] font-semibold text-[var(--primary)] uppercase tracking-wider px-1 mt-3 font-display">Controller · {{ labelOf(selectedController) }}</p>
                  <OptionRenderer v-for="name in controllerOptionNames" :key="'c_' + name" :option-name="name" source="ctrl" />
                </div>
              </div>
            </transition>
          </section>
        </template>

        <!-- ── Settings ── -->
        <SettingsTab v-else-if="state.activeTab === 'settings'" />

      </div>

      <!-- ═══ 任务页固定操作栏：开始 / 停止 ═══ -->
      <div v-if="state.activeTab === 'tasks'" class="action-bar flex-shrink-0 z-20">
        <button
          @click="startTasks"
          :disabled="state.isRunning"
          class="btn-primary flex-1 min-h-11 px-5 rounded-full text-[15px] flex items-center justify-center gap-2"
        >
          <svg class="w-4 h-4" fill="currentColor" viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>
          <span>开始任务</span>
          <span v-if="!state.isRunning" class="text-xs opacity-60">{{ checkedTaskCount }}</span>
        </button>
        <button
          @click="stopTasks"
          :disabled="!state.isRunning"
          class="btn-danger flex-1 min-h-11 px-5 rounded-lg text-[15px] flex items-center justify-center gap-2"
        >
          <svg class="w-4 h-4" fill="currentColor" viewBox="0 0 24 24"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>
          <span>停止任务</span>
        </button>
      </div>

      <!-- ═══ Bottom Tab Bar ═══ -->
      <nav class="surface-glass border-t border-[var(--black-06)] px-2 pt-1.5 pb-2 flex flex-shrink-0 z-20">
        <button
          v-for="tab in TABS"
          :key="tab.key"
          @click="state.activeTab = tab.key"
          class="tabbar-item flex-1"
          :class="state.activeTab === tab.key ? 'on' : ''"
        >
          <!-- home -->
          <svg v-if="tab.key === 'home'" class="w-5 h-5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" d="M3 12l9-8 9 8M5 10v10a1 1 0 001 1h4v-6h4v6h4a1 1 0 001-1V10"/>
          </svg>
          <!-- tasks -->
          <svg v-else-if="tab.key === 'tasks'" class="w-5 h-5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" d="M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01"/>
          </svg>
          <!-- settings -->
          <svg v-else class="w-5 h-5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"/>
            <path stroke-linecap="round" stroke-linejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"/>
          </svg>
          <span class="text-[10px]">{{ tab.label }}</span>
        </button>
      </nav>

      <!-- ═══ Bottom Sheet Drawer ═══ -->
      <OptionDrawer />

    </div>
  </div>
</template>
