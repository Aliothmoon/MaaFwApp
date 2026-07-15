<script setup>
import { computed, ref, watch } from 'vue'
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
const taskPageSection = ref('tasks')
const globalSettingsGroups = computed(() => [
  { key: 'global', label: '通用', names: globalOptionNames.value, source: 'global' },
  { key: 'resource', label: `资源 · ${labelOf(selectedResource.value)}`, names: resourceOptionNames.value, source: 'res' },
  { key: 'controller', label: `控制器 · ${labelOf(selectedController.value)}`, names: controllerOptionNames.value, source: 'ctrl' },
].filter((group) => group.names.length > 0))
const globalSettingsCount = computed(() =>
  globalSettingsGroups.value.reduce((total, group) => total + group.names.length, 0),
)

function selectTaskPageSection(section) {
  taskPageSection.value = section
  if (section === 'settings') state.expandedTaskName = null
}

watch(() => state.activeTab, (nextTab, previousTab) => {
  if (nextTab === 'tasks' && previousTab !== 'tasks') taskPageSection.value = 'tasks'
})
</script>

<template>
  <div class="h-screen flex flex-col app-bg text-[var(--ink)] font-sans select-none">
    <div class="app-shell flex-1 flex flex-col w-full mx-auto overflow-hidden relative">

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

          <div class="task-page-tabs-wrap">
            <div class="responsive-content task-page-tabs" role="tablist" aria-label="任务页内容">
              <button
                type="button"
                role="tab"
                :aria-selected="taskPageSection === 'tasks'"
                :class="{ active: taskPageSection === 'tasks' }"
                @click="selectTaskPageSection('tasks')"
              >任务列表</button>
              <button
                type="button"
                role="tab"
                :aria-selected="taskPageSection === 'settings'"
                :class="{ active: taskPageSection === 'settings' }"
                @click="selectTaskPageSection('settings')"
              >
                全局设置
                <span v-if="globalSettingsCount" class="task-page-tab-count">{{ globalSettingsCount }}</span>
              </button>
            </div>
          </div>

          <template v-if="taskPageSection === 'tasks'">
            <!-- Presets（配置选择器） -->
            <section v-if="state.interface.preset?.length" class="responsive-content px-4 pt-3">
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

            <TaskList :show-heading="false" />
          </template>

          <section
            v-else
            class="responsive-content global-settings-panel px-4 pt-4 pb-6"
            role="tabpanel"
            aria-label="全局设置"
          >
            <div class="global-settings-intro">
              <h2>全局设置</h2>
              <p>这些选项会应用到当前资源、控制器和相关任务。</p>
            </div>

            <div v-for="group in globalSettingsGroups" :key="group.key" class="global-settings-group">
              <p class="global-settings-label">{{ group.label }}</p>
              <OptionRenderer
                v-for="name in group.names"
                :key="`${group.key}_${name}`"
                :option-name="name"
                :source="group.source"
              />
            </div>

            <div v-if="globalSettingsCount === 0" class="global-settings-empty">当前环境没有可配置的全局选项。</div>
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
          class="task-action-button btn-primary flex-1 min-h-11 px-5 text-[15px] flex items-center justify-center gap-2"
        >
          <svg class="w-4 h-4" fill="currentColor" viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>
          <span>开始任务</span>
          <span v-if="!state.isRunning" class="text-xs opacity-60">{{ checkedTaskCount }}</span>
        </button>
        <button
          @click="stopTasks"
          :disabled="!state.isRunning"
          class="task-action-button btn-danger flex-1 min-h-11 px-5 text-[15px] flex items-center justify-center gap-2"
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
