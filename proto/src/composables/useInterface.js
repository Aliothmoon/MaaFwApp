import { reactive, computed, watch } from 'vue'
import { INTERFACE_DATA, I18N_DATA } from '../data.js'
import { substituteInputPlaceholders } from '../utils/pipeline.js'

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
  taskQueue: [],
  showTaskCatalog: false,
  showPipeline: true,
  isRunning: false,
  runProgress: null, // { current, total, taskName }
  lastResult: null, // { level: success|warn|error, message }
  activePresetName: null,
  expandedTaskName: null,
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
    .replace(/!\[[^\]]*]\([^)]*\)/g, '')
    .replace(/\[([^\]]+)]\([^)]*\)/g, '$1')
    .replace(/<br\s*\/?>/gi, ' ')
    .replace(/<[^>]+>/g, '')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/\s+/g, ' ')
    .trim()
}

/* ── Option applicability ── */

function matchesSelectedController(name) {
  const controller = selectedController.value
  if (!controller || typeof name !== 'string') return false
  const expected = name.toLowerCase()
  return [controller.name, controller.type].some((value) => value?.toLowerCase() === expected)
}

export function isOptionApplicable(optName) {
  const def = state.interface.option?.[optName]
  if (!def) return false
  const controllers = asList(def.controller)
  const resources = asList(def.resource)
  if (controllers.length > 0 && !controllers.some((name) => matchesSelectedController(name))) return false
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

const taskCatalog = computed(() => state.interface.task || [])
const visibleTasks = computed(() => {
  const tasksByName = new Map(taskCatalog.value.map((task) => [task.name, task]))
  return state.taskQueue.flatMap((configured) => {
    const task = tasksByName.get(configured.name)
    if (!task) return []
    return [{ task, configured, supported: isTaskSupported(task) }]
  })
})

const selectedTask = computed(() =>
  taskCatalog.value.find((task) => task.name === state.selectedTaskName),
)

const selectedTaskOptionNames = computed(() => {
  const task = selectedTask.value
  if (!task) return []
  return asList(task.option).filter((n) => isOptionApplicable(n))
})

const checkedTaskCount = computed(() =>
  state.taskQueue.filter((task) => task.enabled).length,
)
const queuedTaskCount = computed(() => state.taskQueue.length)
const catalogTaskCount = computed(() => taskCatalog.value.length)

function defaultOptionValue(def) {
  const type = getOptionType(def)
  if (type === 'select' || type === 'switch') return def.default_case ?? null
  if (type === 'checkbox') return asList(def.default_case)
  if (type === 'input') {
    return Object.fromEntries((def.inputs || []).map((input) => {
      const value = input.default ?? ''
      return [
        input.name,
        input.pipeline_type === 'bool' ? String(value).toLowerCase() === 'true' : value,
      ]
    }))
  }
  return null
}

function createDefaultOptionValues() {
  return Object.fromEntries(
    Object.entries(state.interface.option || {}).map(([name, def]) => [name, defaultOptionValue(def)]),
  )
}

export function optionValueOf(optName, taskName = state.selectedTaskName) {
  return state.taskQueue.find((task) => task.name === taskName)?.optionValues?.[optName]
}

export function setOptionValue(optName, value, taskName = state.selectedTaskName) {
  if (!taskName) return
  const configured = state.taskQueue.find((task) => task.name === taskName)
  if (!configured) return
  configured.optionValues[optName] = value
}

/* ═══ Pipeline Override Computation ═══ */

function mergeOptionPipeline(target, optName, visited, values) {
  if (visited.has(optName)) return
  visited.add(optName)

  const def = state.interface.option?.[optName]
  if (!def || !isOptionApplicable(optName)) return

  const type = getOptionType(def)
  const val = values[optName]

  if (type === 'input') {
    if (def.pipeline_override) {
      const data = val || {}
      deepMerge(target, substituteInputPlaceholders(deepClone(def.pipeline_override), def.inputs || [], data))
    }
  } else if (type === 'checkbox') {
    const checked = Array.isArray(val) ? val : []
    for (const caseDef of def.cases || []) {
      if (!checked.includes(caseDef.name)) continue
      if (caseDef.pipeline_override) deepMerge(target, deepClone(caseDef.pipeline_override))
      for (const sub of asList(caseDef.option)) mergeOptionPipeline(target, sub, visited, values)
    }
  } else {
    const selCase = val ?? def.default_case
    const caseDef = (def.cases || []).find((c) => c.name === selCase)
    if (caseDef) {
      if (caseDef.pipeline_override) deepMerge(target, deepClone(caseDef.pipeline_override))
      for (const sub of asList(caseDef.option)) mergeOptionPipeline(target, sub, visited, values)
    }
  }
}

const pipelinePreview = computed(() => {
  const task = selectedTask.value
  if (!task) return {}
  const result = {}
  if (task.pipeline_override) deepMerge(result, deepClone(task.pipeline_override))
  const visited = new Set()
  const values = state.taskQueue.find((configured) => configured.name === task.name)?.optionValues || {}
  for (const n of asList(task.option)) mergeOptionPipeline(result, n, visited, values)
  return result
})

const pipelineJson = computed(() => JSON.stringify(pipelinePreview.value, null, 2))

/* ═══ Initialization ═══ */

function initDefaults() {
  state.selectedControllerName = state.interface.controller[0]?.name || null
  const firstPreset = state.interface.preset?.[0]
  if (firstPreset) applyPreset(firstPreset, { writeLog: false })
  else {
    state.taskQueue = taskCatalog.value.map((task) => ({
      name: task.name,
      enabled: task.default_check === true,
      optionValues: createDefaultOptionValues(),
    }))
  }
  state.selectedTaskName = state.taskQueue[0]?.name || null
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

function applyPreset(preset, { writeLog = true } = {}) {
  state.activePresetName = preset.name
  const presetTasks = asList(preset.task)
  const knownTasks = new Set(taskCatalog.value.map((task) => task.name))
  state.taskQueue = presetTasks.flatMap((presetTask, index) => {
    if (!knownTasks.has(presetTask.name)) return []
    if (presetTasks.findIndex((task) => task.name === presetTask.name) !== index) return []

    const optionValues = createDefaultOptionValues()
    for (const [optName, optVal] of Object.entries(presetTask.option || {})) {
      const def = state.interface.option?.[optName]
      if (!def) continue
      const type = getOptionType(def)
      if (type === 'checkbox') {
        optionValues[optName] = Array.isArray(optVal) ? [...optVal] : [optVal]
      } else if (type === 'input') {
        if (typeof optVal === 'object' && !Array.isArray(optVal)) optionValues[optName] = { ...optVal }
      } else {
        optionValues[optName] = optVal
      }
    }

    return [{
      name: presetTask.name,
      enabled: presetTask.enabled !== false,
      optionValues,
    }]
  })

  state.expandedTaskName = null
  state.selectedTaskName = state.taskQueue[0]?.name || null
  if (writeLog) addLog('info', `已切换配置：${labelOf(preset)}`)
}

function addTasks(taskNames) {
  if (state.isRunning) return 0
  const tasksByName = new Map(taskCatalog.value.map((task) => [task.name, task]))
  const queuedNames = new Set(state.taskQueue.map((task) => task.name))
  let added = 0

  for (const taskName of taskNames) {
    if (queuedNames.has(taskName) || !tasksByName.has(taskName)) continue
    state.taskQueue.push({
      name: taskName,
      enabled: true,
      optionValues: createDefaultOptionValues(),
    })
    queuedNames.add(taskName)
    added++
  }

  state.showTaskCatalog = false
  if (added > 0) addLog('info', `已添加 ${added} 个任务`)
  return added
}

function removeTask(taskName) {
  if (state.isRunning) return false

  const index = state.taskQueue.findIndex((task) => task.name === taskName)
  if (index < 0) return false

  const removedTask = taskCatalog.value.find((task) => task.name === taskName)
  state.taskQueue.splice(index, 1)

  if (state.expandedTaskName === taskName) state.expandedTaskName = null
  if (state.selectedTaskName === taskName) {
    state.selectedTaskName = state.taskQueue[index]?.name || state.taskQueue[index - 1]?.name || null
  }

  addLog('info', `已从当前配置移除：${labelOf(removedTask)}`)
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
  const tasksByName = new Map(taskCatalog.value.map((task) => [task.name, task]))
  const checked = state.taskQueue
    .filter((task) => task.enabled)
    .map((task) => tasksByName.get(task.name))
    .filter(Boolean)
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

function isTaskSupported(task) {
  const resources = asList(task.resource)
  const controllers = asList(task.controller)
  const resourceOk = resources.length === 0 || resources.includes(state.selectedResourceName)
  const controllerOk = controllers.length === 0 || controllers.some((name) => matchesSelectedController(name))
  return resourceOk && controllerOk
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
    taskCatalog, visibleTasks, selectedTask,
    selectedTaskOptionNames,
    checkedTaskCount, queuedTaskCount, catalogTaskCount,
    pipelinePreview, pipelineJson,
    optionValueOf, setOptionValue,
    applyPreset, addTasks, removeTask, toggleStart, toggleLang, addLog, highlightJson,
    isTaskSupported,
    optCount: (task) => asList(task.option).filter((n) => isOptionApplicable(n)).length,
  }
}
