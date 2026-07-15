<script setup>
import { computed } from 'vue'
import { useInterface, asList, getOptionType, isSwitchYes } from '../composables/useInterface.js'

const props = defineProps({
  optionName: { type: String, required: true },
  level: { type: Number, default: 0 },
  source: { type: String, default: '' },
})

const { state, t, labelOf, descOf, isOptionApplicable } = useInterface()

const optDef = computed(() => state.interface.option?.[props.optionName])
const optType = computed(() => getOptionType(optDef.value))
const applicable = computed(() => isOptionApplicable(props.optionName))
const switchOn = computed(() => isSwitchYes(state.optionValues[props.optionName]))

const activeSubOptions = computed(() => {
  const def = optDef.value
  if (!def) return []
  const val = state.optionValues[props.optionName]
  const result = []
  if (optType.value === 'checkbox') {
    for (const c of def.cases || []) { if ((val || []).includes(c.name)) result.push(...asList(c.option)) }
  } else if (optType.value !== 'input') {
    const sel = val || def.default_case || def.cases?.[0]?.name
    const cd = (def.cases || []).find((c) => c.name === sel)
    if (cd) result.push(...asList(cd.option))
  }
  return result
})

function validateInput(inp) {
  const val = (state.optionValues[props.optionName] || {})[inp.name] || ''
  if (inp.verify) { try { if (val && !new RegExp(inp.verify).test(val)) return false } catch {} }
  return true
}
function toggleSwitch() {
  const cases = optDef.value.cases || []
  const next = switchOn.value ? cases.find((c) => !isSwitchYes(c.name)) : cases.find((c) => isSwitchYes(c.name))
  if (next) state.optionValues[props.optionName] = next.name
}
function toggleCheckbox(name) {
  const arr = state.optionValues[props.optionName] || []
  const i = arr.indexOf(name)
  if (i >= 0) arr.splice(i, 1); else arr.push(name)
  state.optionValues[props.optionName] = [...arr]
}
</script>

<template>
  <div v-if="optDef && applicable" :style="{ marginLeft: level * 14 + 'px' }">
    <div class="px-3 py-2.5 surface-option">

      <!-- Switch -->
      <div v-if="optType === 'switch'" class="flex items-center gap-3">
        <div class="flex-1 min-w-0">
          <div class="flex items-center gap-1.5">
            <span v-if="source" :class="'badge badge-' + source">{{ source }}</span>
            <span class="text-[14px] font-semibold text-[var(--ink)]">{{ labelOf(optDef) }}</span>
          </div>
          <p v-if="descOf(optDef)" class="text-[12px] leading-relaxed text-[var(--muted)] mt-1">{{ descOf(optDef) }}</p>
        </div>
        <button @click="toggleSwitch" class="switch-track" :class="switchOn ? 'on' : 'off'"><span class="switch-thumb" /></button>
      </div>

      <!-- Select -->
      <div v-else-if="optType === 'select'">
        <div class="flex items-center gap-1.5 mb-1.5">
          <span v-if="source" :class="'badge badge-' + source">{{ source }}</span>
          <span class="text-[14px] font-semibold text-[var(--ink)]">{{ labelOf(optDef) }}</span>
        </div>
        <select v-model="state.optionValues[optionName]" class="ctrl-select">
          <option v-for="c in optDef.cases" :key="c.name" :value="c.name">{{ labelOf(c) }}</option>
        </select>
      </div>

      <!-- Checkbox -->
      <div v-else-if="optType === 'checkbox'">
        <div class="flex items-center gap-1.5 mb-2">
          <span v-if="source" :class="'badge badge-' + source">{{ source }}</span>
          <span class="text-[14px] font-semibold text-[var(--ink)]">{{ labelOf(optDef) }}</span>
        </div>
        <div class="flex flex-wrap gap-1.5">
          <button v-for="c in optDef.cases" :key="c.name" @click="toggleCheckbox(c.name)"
            class="flex-1 min-w-[72px] py-2 chip text-xs text-center"
            :class="(state.optionValues[optionName] || []).includes(c.name) ? 'on' : ''">{{ labelOf(c) }}</button>
        </div>
      </div>

      <!-- Input -->
      <div v-else-if="optType === 'input'">
        <div class="flex items-center gap-1.5 mb-2">
          <span v-if="source" :class="'badge badge-' + source">{{ source }}</span>
          <span class="text-[14px] font-semibold text-[var(--ink)]">{{ labelOf(optDef) }}</span>
        </div>
        <div class="space-y-2">
          <div v-for="inp in optDef.inputs" :key="inp.name">
            <div class="flex items-center justify-between mb-1">
              <label class="text-[12px] text-[var(--muted)]">{{ t(inp.label || inp.name) }}</label>
              <span v-if="inp.pipeline_type" class="text-[9px] font-mono px-1.5 py-0.5 rounded-[5px] bg-[var(--black-05)] text-[var(--muted)]">{{ inp.pipeline_type }}</span>
            </div>
            <input v-if="inp.pipeline_type !== 'bool'" type="text" v-model="state.optionValues[optionName][inp.name]"
              :placeholder="inp.default" class="ctrl-input" :class="{ invalid: !validateInput(inp) }" />
            <label v-else class="w-11 h-11 flex items-center justify-center">
              <input type="checkbox" class="cb" v-model="state.optionValues[optionName][inp.name]" />
            </label>
            <p v-if="!validateInput(inp)" class="text-[10px] text-[var(--muted)] mt-1">Invalid input</p>
          </div>
        </div>
      </div>
    </div>

    <div v-if="activeSubOptions.length > 0" class="mt-1.5 space-y-1.5 border-l-2 border-[color:var(--primary)]/15 pl-2.5">
      <OptionRenderer v-for="subName in activeSubOptions" :key="subName" :option-name="subName" :level="level + 1" />
    </div>
  </div>
</template>
