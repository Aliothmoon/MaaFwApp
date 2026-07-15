import { reactive, computed, watch } from 'vue'
import { INTERFACE_DATA, I18N_DATA } from '../data.js'

/* ═══ Utilities ═══ */

export function asList(v) {
  if (v == null) return []
  return Array.isArray(v) ? [...v] : [v]
}

export function deepClone(obj) {
  if (obj == null || typeof obj !== 'object') return obj
  return JSON.parse(JSON.stringify(obj))
}

function deepMerge(target, ...sources) {
  for (const src of sources) {
    if (src == null || typeof src !== 'object') continue
    for (const key of Object.keys(src)) {
      const sv = src[key]
      if (sv && typeof sv === 'object' && !Array.isArray(sv) &&
          target[key] && typeof target[key] === 'object' && !Array.isArray(target[key])) {
        deepMerge(target[key], sv)
      } else {
        target[key] = deepClone(sv)
      }
    }
  }
  return target
}

export function getOptionType(optDef) {
  if (!optDef) return 'select'
  if (optDef.type) return optDef.type
  if (optDef.inputs) return 'input'
  return 'select'
}

export function isSwitchYes(name) {
  return ['Yes', 'yes', 'Y', 'y'].includes(name)
}

/* ═══ State ═══ */

const state = reactive({
  interface: INTERFACE_DATA,
  lang: 'zh_cn',
  activeTab: 'home', // home | tasks | settings
  selectedControllerName: null,
  selectedResourceName: null,
  selectedTaskName: null,
  taskChecks: {},
  optionValues: {},
  showPipeline: true,
  isRunning: false,
  runProgress: null, // { current, total, taskName }
  lastResult: null, // { level: success|warn|error, message }
  activePresetName: null,
  expandedTaskName: null,
  showGlobalSettings: false,
  isConnected: true,
  previewFPS: 30,
  logEntries: [],
})

/* ── i18n ── */

export function t(str) {
  if (typeof str !== 'string') return str
  if (!str.startsWith('$')) return str
  const key = str.slice(1)
  const dict = I18N_DATA[state.lang] || I18N_DATA.zh_cn
  return dict[key] || key
}

export function labelOf(obj) {
  if (!obj) return ''
  return t(obj.label || obj.name || '')
}

export function descOf(obj) {
  if (!obj || !obj.description) return ''
  return t(obj.description)
}

/* ── Option applicability ── */

export function isOptionApplicable(optName) {
  const def = state.interface.option?.[optName]
  if (!def) return false
  const controllers = asList(def.controller)
  const resources = asList(def.resource)
  if (controllers.length > 0 && !controllers.includes(state.selectedControllerName)) return false
  if (resources.length > 0 && !resources.includes(state.selectedResourceName)) return false
  return true
}

/* ═══ Computed ═══ */

const availableResources = computed(() => {
  const ctrl = state.selectedControllerName
  return (state.interface.resource || []).filter((r) => {
    const rcs = asList(r.controller)
    return rcs.length === 0 || rcs.includes(ctrl)
  })
})

const selectedController = computed(() =>
  (state.interface.controller || []).find((c) => c.name === state.selectedControllerName),
)

const selectedResource = computed(() =>
  (state.interface.resource || []).find((r) => r.name === state.selectedResourceName),
)

const visibleTasks = computed(() =>
  (state.interface.task || []).map((task) => {
    const resources = asList(task.resource)
    const controllers = asList(task.controller)
    const resourceOk = resources.length === 0 || resources.includes(state.selectedResourceName)
    const controllerOk = controllers.length === 0 || controllers.includes(state.selectedControllerName)
    return { task, supported: resourceOk && controllerOk }
  }),
)

const selectedTask = computed(() =>
  (state.interface.task || []).find((t) => t.name === state.selectedTaskName),
)

const globalOptionNames = computed(() =>
  asList(state.interface.global_option).filter((n) => isOptionApplicable(n)),
)

const resourceOptionNames = computed(() => {
  const r = selectedResource.value
  if (!r) return []
  return asList(r.option).filter((n) => isOptionApplicable(n))
})

const controllerOptionNames = computed(() => {
  const c = selectedController.value
  if (!c) return []
  return asList(c.option).filter((n) => isOptionApplicable(n))
})

const selectedTaskOptionNames = computed(() => {
  const task = selectedTask.value
  if (!task) return []
  return asList(task.option).filter((n) => isOptionApplicable(n))
})

const checkedTaskCount = computed(() =>
  Object.values(state.taskChecks).filter((v) => v === true).length,
)

/* ═══ Pipeline Override Computation ═══ */

function replacePlaceholders(obj, data) {
  const json = JSON.stringify(obj)
  const replaced = json.replace(/\{([^}]+)\}/g, (match, key) => {
    const val = data[key]
    if (val === undefined) return match
    const numVal = Number(val)
    if (!isNaN(numVal) && val !== '') return String(numVal)
    return JSON.stringify(val)
  })
  return JSON.parse(replaced)
}

function mergeOptionPipeline(target, optName, visited) {
  if (visited.has(optName)) return
  visited.add(optName)

  const def = state.interface.option?.[optName]
  if (!def || !isOptionApplicable(optName)) return

  const type = getOptionType(def)
  const val = state.optionValues[optName]

  if (type === 'input') {
    if (def.pipeline_override) {
      const data = val || {}
      deepMerge(target, replacePlaceholders(deepClone(def.pipeline_override), data))
    }
  } else if (type === 'checkbox') {
    const checked = Array.isArray(val) ? val : []
    for (const caseDef of def.cases || []) {
      if (!checked.includes(caseDef.name)) continue
      if (caseDef.pipeline_override) deepMerge(target, deepClone(caseDef.pipeline_override))
      for (const sub of asList(caseDef.option)) mergeOptionPipeline(target, sub, visited)
    }
  } else {
    const selCase = val || def.default_case || def.cases?.[0]?.name
    const caseDef = (def.cases || []).find((c) => c.name === selCase)
    if (caseDef) {
      if (caseDef.pipeline_override) deepMerge(target, deepClone(caseDef.pipeline_override))
      for (const sub of asList(caseDef.option)) mergeOptionPipeline(target, sub, visited)
    }
  }
}

const pipelinePreview = computed(() => {
  const task = selectedTask.value
  if (!task) return {}
  const result = {}
  if (task.pipeline_override) deepMerge(result, deepClone(task.pipeline_override))
  const visited = new Set()
  for (const n of asList(state.interface.global_option)) mergeOptionPipeline(result, n, visited)
  for (const n of asList(selectedResource.value?.option)) mergeOptionPipeline(result, n, visited)
  for (const n of asList(selectedController.value?.option)) mergeOptionPipeline(result, n, visited)
  for (const n of asList(task.option)) mergeOptionPipeline(result, n, visited)
  return result
})

const pipelineJson = computed(() => JSON.stringify(pipelinePreview.value, null, 2))

/* ═══ Initialization ═══ */

function initDefaults() {
  state.selectedControllerName = state.interface.controller[0]?.name || null
  for (const task of state.interface.task || []) {
    state.taskChecks[task.name] = task.default_check === true
  }
  for (const [name, def] of Object.entries(state.interface.option || {})) {
    const type = getOptionType(def)
    if (type === 'select' || type === 'switch') {
      state.optionValues[name] = def.default_case || def.cases?.[0]?.name || ''
    } else if (type === 'checkbox') {
      state.optionValues[name] = asList(def.default_case)
    } else if (type === 'input') {
      const data = {}
      for (const inp of def.inputs || []) data[inp.name] = inp.default ?? ''
      state.optionValues[name] = data
    }
  }
  const firstOk = state.interface.task?.find((t) => {
    const r = asList(t.resource)
    return r.length === 0
  })
  state.selectedTaskName = firstOk?.name || state.interface.task?.[0]?.name || null
}

initDefaults()

/* ── Cascade: controller → resource ── */
watch(() => state.selectedControllerName, () => {
  const avail = availableResources.value
  if (avail.length > 0 && !avail.some((r) => r.name === state.selectedResourceName)) {
    state.selectedResourceName = avail[0].name
  } else if (avail.length > 0 && !state.selectedResourceName) {
    state.selectedResourceName = avail[0].name
  }
}, { immediate: true })

/* ═══ Actions ═══ */

function addLog(level, message) {
  state.logEntries.unshift({ level, message, time: new Date().toLocaleTimeString() })
  if (state.logEntries.length > 50) state.logEntries.pop()
}

function applyPreset(preset) {
  state.activePresetName = preset.name
  const presetTasks = asList(preset.task)
  for (const task of state.interface.task || []) {
    const pt = presetTasks.find((pt) => pt.name === task.name)
    state.taskChecks[task.name] = pt ? pt.enabled !== false : false
  }
  for (const pt of presetTasks) {
    if (!pt.option) continue
    for (const [optName, optVal] of Object.entries(pt.option)) {
      const def = state.interface.option?.[optName]
      if (!def) continue
      const type = getOptionType(def)
      if (type === 'checkbox') {
        state.optionValues[optName] = Array.isArray(optVal) ? [...optVal] : [optVal]
      } else if (type === 'input') {
        if (typeof optVal === 'object' && !Array.isArray(optVal)) state.optionValues[optName] = { ...optVal }
      } else {
        state.optionValues[optName] = optVal
      }
    }
  }
  addLog('info', `Preset applied: ${labelOf(preset)}`)
}

function removeTask(taskName) {
  if (state.isRunning) return false

  const tasks = state.interface.task || []
  const index = tasks.findIndex((task) => task.name === taskName)
  if (index < 0) return false

  const [removedTask] = tasks.splice(index, 1)
  delete state.taskChecks[taskName]

  const presets = state.interface.preset || []
  for (let presetIndex = presets.length - 1; presetIndex >= 0; presetIndex--) {
    const preset = presets[presetIndex]
    const remainingTasks = asList(preset.task).filter((task) => task.name !== taskName)
    if (remainingTasks.length === 0) presets.splice(presetIndex, 1)
    else if (Array.isArray(preset.task)) preset.task = remainingTasks
    else if (remainingTasks.length > 0) preset.task = remainingTasks[0]
  }

  if (state.expandedTaskName === taskName) state.expandedTaskName = null
  if (state.selectedTaskName === taskName) {
    state.selectedTaskName = tasks[index]?.name || tasks[index - 1]?.name || null
  }

  state.activePresetName = null
  addLog('info', `Task deleted: ${labelOf(removedTask)}`)
  return true
}

function toggleStart() {
  if (state.isRunning) {
    state.isRunning = false
    state.runProgress = null
    state.lastResult = { level: 'warn', message: '已取消' }
    addLog('warn', 'Task execution stopped.')
    return
  }
  const checked = (state.interface.task || []).filter((t) => state.taskChecks[t.name])
  if (checked.length === 0) {
    addLog('error', 'No tasks selected.')
    return
  }
  state.isRunning = true
  state.lastResult = null
  state.runProgress = { current: 0, total: checked.length, taskName: labelOf(checked[0]) }
  addLog('success', `Starting ${checked.length} task(s): ${checked.map(labelOf).join(', ')}`)
  let i = 0
  const timer = setInterval(() => {
    if (i >= checked.length || !state.isRunning) {
      clearInterval(timer)
      if (state.isRunning) {
        state.isRunning = false
        state.runProgress = null
        state.lastResult = { level: 'success', message: `全部完成（${checked.length} 个任务）` }
        addLog('success', 'All tasks completed.')
      }
      return
    }
    state.runProgress = { current: i, total: checked.length, taskName: labelOf(checked[i]) }
    addLog('info', `[${i + 1}/${checked.length}] Running: ${labelOf(checked[i])}`)
    i++
  }, 1500)
}

function toggleLang() {
  state.lang = state.lang === 'zh_cn' ? 'en_us' : 'zh_cn'
}

function highlightJson(json) {
  return json
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(
      /("(\\u[a-zA-Z0-9]{4}|\\[^u]|[^\\"])*"(\s*:)?|\b(true|false|null)\b|-?\d+\.?\d*([eE][+\-]?\d+)?)/g,
      (match) => {
        let cls = 'text-[var(--canvas)]'
        if (/^"/.test(match)) cls = /:$/.test(match) ? 'text-[var(--primary-on-dark)]' : 'text-[var(--body-muted)]'
        else if (/true|false|null/.test(match)) cls = 'text-[var(--primary-on-dark)]'
        return `<span class="${cls}">${match}</span>`
      },
    )
}

/* ═══ Export ═══ */

export function useInterface() {
  return {
    state,
    t, labelOf, descOf, asList, isOptionApplicable, isSwitchYes, getOptionType,
    availableResources, selectedController, selectedResource,
    visibleTasks, selectedTask,
    globalOptionNames, resourceOptionNames, controllerOptionNames, selectedTaskOptionNames,
    checkedTaskCount,
    pipelinePreview, pipelineJson,
    applyPreset, removeTask, toggleStart, toggleLang, addLog, highlightJson,
    optCount: (task) => asList(task.option).filter((n) => isOptionApplicable(n)).length,
  }
}
