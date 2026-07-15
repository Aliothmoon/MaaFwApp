export const INTERFACE_DATA = {
  interface_version: 2,
  languages: { zh_cn: 'interface_zh.json', en_us: 'interface_en.json' },
  name: 'MyDemo3',
  label: '$MyDemo3',
  title: 'Demo3 v1.0.0',
  mirrorchyan_rid: 'demo3',
  mirrorchyan_multiplatform: false,
  github: 'https://github.com/MAA1999/M9A',
  version: '1.0.0',
  contact: 'QQ群: 592974962',
  license: 'MIT',
  welcome: '欢迎使用 MaaFramework ProjectInterface V2 Web Demo!',
  description:
    'MaaFramework is a framework for building automation applications. This is a sample project interface for demonstration purposes.',
  agent: { child_exec: 'python', child_args: ['sample/python/demo3_agent.py'] },
  controller: [
    {
      name: 'Android', label: '$安卓端', description: '通过 ADB 连接安卓设备',
      type: 'Adb', display_short_side: 720, display_raw: false,
      option: ['战斗自动闪避'],
    },
    {
      name: 'Windows', label: '$电脑端', description: '通过 Win32 API 连接 Windows 应用程序',
      type: 'Win32',
      win32: { class_regex: '.*', window_regex: 'Visual Studio', screencap: 'PrintWindow', mouse: 'PostMessageWithCursorPos', keyboard: 'PostMessageWithCursorPos' },
    },
    {
      name: 'macOS', label: '$macOS端', description: '通过 macOS 原生 API 连接 macOS 应用程序',
      type: 'MacOS',
      macos: { title_regex: '关于本机', screencap: 'ScreenCaptureKit', input: 'GlobalEvent' },
    },
  ],
  resource: [
    {
      name: 'Official', label: '$官服资源', description: '官服资源包',
      path: ['resource'], controller: ['Android'],
      option: ['战斗划火柴'],
    },
    { name: 'Bilibili', label: '$B站资源', path: ['resource', 'resource_bilibili'] },
  ],
  task: [
    { name: '收取荒原', label: '$收取荒原', entry: 'Wilderness', default_check: true, description: '收取荒原资源，若不勾选，则不会执行此任务' },
    { name: '每日心相（意志解析）', label: '$每日心相', entry: 'Psychube' },
    { name: '常规作战', label: '$常规作战', entry: 'Combat', option: ['作战关卡', '复现次数', '刷完全部体力', '战斗划火柴'] },
    {
      name: '活动：绿湖噩梦 17 艰难（活动已结束）', label: '$活动任务', entry: 'ANightmareAtGreenLake',
      resource: ['Official'], option: ['复现次数', '刷完全部体力'],
      pipeline_override: {
        EnterTheShow: { next: 'ANightmareAtGreenLake' },
        TargetStageName: { expected: '17' },
        StageDifficulty: { next: 'ActivityStageDifficulty' },
      },
    },
    { name: '领取奖励', label: '$领取奖励', entry: 'Awards' },
  ],
  option: {
    作战关卡: {
      type: 'select', label: '$选择作战关卡', description: '选择要刷的关卡',
      resource: ['Official'],
      cases: [
        {
          name: '3-9 厄险（百灵百验鸟）', label: '$3-9厄险', description: '刷百灵鸟',
          option: ['使用理智药', '刷完xxx'],
          pipeline_override: {
            EnterTheShow: { next: 'MainChapter_3' },
            TargetStageName: { expected: '09' },
            StageDifficulty: { next: 'StageDifficulty_Hard' },
          },
        },
        {
          name: '4-20 厄险（双头形骨架）', label: '$4-20厄险',
          pipeline_override: {
            EnterTheShow: { next: 'MainChapter_4' },
            TargetStageName: { expected: '20' },
            StageDifficulty: { next: 'StageDifficulty_Hard' },
          },
        },
      ],
    },
    自定义关卡: {
      type: 'input', label: '自定义关卡', description: '自己选打什么关',
      inputs: [
        { name: '章节号', label: '$章节号', description: '关卡章节号', default: '4', pipeline_type: 'string', verify: '^\\d+$' },
        { name: '难度', label: '$难度', default: 'Hard', pipeline_type: 'string', verify: '^(Normal|Hard)$' },
        { name: '超时时间', label: '$超时时间', default: '20000', pipeline_type: 'int', verify: '^\\d+$' },
      ],
      pipeline_override: {
        EnterTheShow: { next: 'MainChapter_{章节号}', timeout: '{超时时间}' },
        TargetStageName: { expected: '{关卡号}' },
        StageDifficulty: { next: 'StageDifficulty_{难度}' },
      },
    },
    复现次数: {
      description: '打几次',
      cases: [
        { name: 'x1', label: '$1次', description: '打1次', pipeline_override: { SetReplaysTimes: { expected: '1' } } },
        { name: 'x2', label: '$2次', pipeline_override: { SetReplaysTimes: { expected: '2' } } },
        { name: 'x3', label: '$3次', pipeline_override: { SetReplaysTimes: { expected: '3' } } },
        { name: 'x4', label: '$4次', pipeline_override: { SetReplaysTimes: { expected: '4' } } },
      ],
    },
    刷完全部体力: {
      type: 'switch', label: '$刷完全部体力', description: '是否刷完全部体力',
      cases: [
        { name: 'Yes', label: '$是', pipeline_override: { AllIn: { enabled: true } } },
        { name: 'No', label: '$否', pipeline_override: { AllIn: { enabled: false } } },
      ],
    },
    使用理智药: {
      type: 'switch', label: '$使用理智药', description: '体力不足时是否使用理智药',
      cases: [
        { name: 'Yes', label: '$使用', pipeline_override: { UseSanityPotion: { enabled: true } } },
        { name: 'No', label: '$不使用', pipeline_override: { UseSanityPotion: { enabled: false } } },
      ],
    },
    刷完xxx: {
      type: 'select', label: '$刷完后操作', description: '刷完关卡后执行的操作',
      default_case: '返回主界面',
      cases: [
        { name: '返回主界面', label: '$返回主界面', pipeline_override: { AfterFarming: { next: 'ReturnToMainMenu' } } },
        { name: '继续战斗', label: '$继续战斗', pipeline_override: { AfterFarming: { next: 'ContinueBattle' } } },
        { name: '退出游戏', label: '$退出游戏', pipeline_override: { AfterFarming: { next: 'ExitGame' } } },
      ],
    },
    战斗划火柴: {
      type: 'checkbox', label: '$战斗划火柴',
      description: '选择要启用的划火柴功能，可多选。多个被选中 case 按 cases 定义顺序合并，与用户勾选顺序无关',
      default_case: ['普通划火柴', '蓄力划火柴'],
      cases: [
        { name: '普通划火柴', label: '$普通划火柴', description: '启用普通划火柴', pipeline_override: { NormalMatch: { enabled: true } } },
        { name: '蓄力划火柴', label: '$蓄力划火柴', description: '启用蓄力划火柴', pipeline_override: { ChargedMatch: { enabled: true } } },
        { name: '连续划火柴', label: '$连续划火柴', description: '启用连续划火柴', pipeline_override: { ComboMatch: { enabled: true } } },
      ],
    },
    战斗自动闪避: {
      type: 'switch', label: '$战斗自动闪避', description: '是否启用战斗中自动闪避功能',
      default_case: 'No',
      cases: [
        { name: 'Yes', label: '$启用', pipeline_override: { AutoDodge: { enabled: true } } },
        { name: 'No', label: '$不启用', pipeline_override: { AutoDodge: { enabled: false } } },
      ],
    },
  },
  global_option: ['战斗划火柴', '战斗自动闪避'],
  preset: [
    {
      name: '刷日常', label: '$刷日常', description: '每日例行套餐：荒原 + 心相 + 3-9 + 领奖',
      task: [
        { name: '收取荒原', enabled: true },
        { name: '每日心相（意志解析）', enabled: true },
        { name: '常规作战', enabled: true, option: { 作战关卡: '3-9 厄险（百灵百验鸟）', 复现次数: 'x3', 刷完全部体力: 'No', 战斗划火柴: ['普通划火柴', '蓄力划火柴'] } },
        { name: '领取奖励', enabled: true },
        { name: '活动：绿湖噩梦 17 艰难（活动已结束）', enabled: false },
      ],
    },
    {
      name: 'ALL IN', label: '$全力刷本', description: '全力刷特定关卡，消耗所有体力',
      task: [
        { name: '常规作战', enabled: true, option: { 作战关卡: '4-20 厄险（双头形骨架）', 复现次数: 'x4', 刷完全部体力: 'Yes', 战斗划火柴: ['普通划火柴', '蓄力划火柴', '连续划火柴'] } },
      ],
    },
  ],
}

export const I18N_DATA = {
  zh_cn: {
    MyDemo3: '示例程序3',
    安卓端: '安卓设备', 电脑端: 'Windows 桌面应用', macOS端: 'macOS 设备',
    官服资源: '官方服务器资源包', B站资源: 'Bilibili 服务器资源包',
    收取荒原: '收取荒原资源', 每日心相: '每日心相（意志解析）',
    常规作战: '常规作战任务', 活动任务: '活动：绿湖噩梦 17 艰难', 领取奖励: '领取每日奖励',
    选择作战关卡: '请选择要刷的关卡',
    '3-9厄险': '3-9 厄险（百灵百验鸟）', '4-20厄险': '4-20 厄险（双头形骨架）',
    章节号: '章节号', 难度: '关卡难度', 超时时间: '等待超时时间（毫秒）',
    '1次': '复现 1 次', '2次': '复现 2 次', '3次': '复现 3 次', '4次': '复现 4 次',
    是: '是', 否: '否',
    刷完全部体力: '是否刷完全部体力',
    使用理智药: '是否使用理智药', 使用: '使用', 不使用: '不使用',
    刷完后操作: '刷完关卡后的操作',
    返回主界面: '返回主界面', 继续战斗: '继续战斗', 退出游戏: '退出游戏',
    战斗划火柴: '战斗划火柴功能',
    普通划火柴: '普通划火柴', 蓄力划火柴: '蓄力划火柴', 连续划火柴: '连续划火柴',
    战斗自动闪避: '战斗自动闪避', 启用: '启用', 不启用: '不启用',
    刷日常: '刷日常', 全力刷本: '全力刷本',
  },
  en_us: {
    MyDemo3: 'Demo Program 3',
    安卓端: 'Android Device', 电脑端: 'Windows Desktop', macOS端: 'macOS Device',
    官服资源: 'Official Server Resource Pack', B站资源: 'Bilibili Server Resource Pack',
    收取荒原: 'Collect Wilderness Resources', 每日心相: 'Daily Psychube (Wilderness Analysis)',
    常规作战: 'Regular Combat Mission', 活动任务: 'Event: A Nightmare At Green Lake 17 Dire',
    领取奖励: 'Claim Daily Rewards',
    选择作战关卡: 'Select Combat Stage',
    '3-9厄险': "3-9 Dire (Lark)", '4-20厄险': '4-20 Dire (Two-Headed Skeleton)',
    章节号: 'Chapter Number', 难度: 'Stage Difficulty', 超时时间: 'Timeout (ms)',
    '1次': 'Replay 1x', '2次': 'Replay 2x', '3次': 'Replay 3x', '4次': 'Replay 4x',
    是: 'Yes', 否: 'No',
    刷完全部体力: 'Use All Stamina',
    使用理智药: 'Use Sanity Potion', 使用: 'Use', 不使用: "Don't Use",
    刷完后操作: 'Action After Farming',
    返回主界面: 'Return to Main Menu', 继续战斗: 'Continue Battle', 退出游戏: 'Exit Game',
    战斗划火柴: 'Combat Match Features',
    普通划火柴: 'Normal Match', 蓄力划火柴: 'Charged Match', 连续划火柴: 'Combo Match',
    战斗自动闪避: 'Auto Dodge', 启用: 'Enabled', 不启用: 'Disabled',
    刷日常: 'Daily Routine', 全力刷本: 'ALL IN',
  },
}
