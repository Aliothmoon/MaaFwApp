const taskFragments = import.meta.glob(
  '../../app/src/main/assets/sample/tasks/**/*.json',
  { eager: true, import: 'default' },
)

const RESOURCE_DEFINITIONS = [
  { name: '官服', path: ['resource/base'] },
  { name: 'B服', path: ['resource/base', 'resource/bilibili'] },
  { name: '国际服-英文', path: ['resource/base', 'resource/global_en'] },
  { name: '日服', path: ['resource/base', 'resource/global_jp'] },
  { name: '韩服', path: ['resource/base', 'resource/global_kr'] },
  { name: '华为服', path: ['resource/base', 'resource/huawei'] },
  { name: '小米服', path: ['resource/base', 'resource/mi'] },
  { name: 'OPPO服', path: ['resource/base', 'resource/oppo'] },
  { name: '繁中服', path: ['resource/base', 'resource/tw'] },
]

function mergeSampleFragments(fragments) {
  const tasks = []
  const options = {}
  const presets = []
  const taskNames = new Set()
  const presetNames = new Set()

  for (const [, fragment] of Object.entries(fragments).sort(([left], [right]) => left.localeCompare(right))) {
    for (const task of fragment.task || []) {
      if (taskNames.has(task.name)) continue
      taskNames.add(task.name)
      tasks.push({
        ...task,
        default_check: task.check === true,
      })
    }

    for (const [name, option] of Object.entries(fragment.option || {})) {
      if (!(name in options)) options[name] = option
    }

    for (const preset of fragment.preset || []) {
      if (presetNames.has(preset.name)) continue
      presetNames.add(preset.name)
      presets.push(preset)
    }
  }

  return { tasks, options, presets }
}

const sample = mergeSampleFragments(taskFragments)

export const INTERFACE_DATA = {
  interface_version: 2,
  name: 'sample',
  version: null,
  controller: [
    {
      name: 'Android',
      label: 'Android',
      type: 'ADB',
      display_short_side: 720,
      display_raw: false,
    },
  ],
  resource: RESOURCE_DEFINITIONS,
  task: sample.tasks,
  option: sample.options,
  preset: sample.presets,
  global_option: [],
}

// 当前 sample PI 使用直接中文文案，没有额外 languages 文件。
export const I18N_DATA = {
  zh_cn: {},
  en_us: {},
}
