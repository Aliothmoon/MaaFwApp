# MaaFramework ProjectInterface V2 — 移动端 Web 原型

本文件夹是 MaaFramework ProjectInterface V2 协议的**移动端** Web 演示原型，模拟 Android 端 MaaFwApp 的 UI 布局。

## 技术栈

| 选型 | 理由 |
|------|------|
| **Vue 3 + Vite** | SFC 单文件组件，`<script setup>` 语法简洁；HMR 热更新即时预览；递归组件天然适配 `case.option` 无限嵌套 |
| **Tailwind CSS 3** | 原子化类名保证样式一致性，暗色主题开箱即用 |

## 快速运行

```bash
cd proto
npm install      # 安装依赖
npm run dev      # 启动开发服务器 (http://localhost:5173)
npm run build    # 构建生产版本
```

## 文件结构

```
proto/
├── index.html              Vite 入口 HTML
├── package.json
├── vite.config.js
├── tailwind.config.js
├── postcss.config.js
├── src/
│   ├── main.js             Vue 应用入口
│   ├── style.css           全局样式 + Tailwind 指令
│   ├── data.js             interface.json 示例数据 + i18n 翻译
│   ├── App.vue             根组件 (移动端布局：Header + Toolbar + Content + BottomBar)
│   ├── composables/
│   │   └── useInterface.js  全局状态管理 + 所有业务逻辑
│   └── components/
│       ├── Toolbar.vue          控制器/资源选择器（紧凑横排）
│       ├── TaskList.vue         任务卡片列表（手风琴展开）
│       ├── OptionRenderer.vue   递归选项渲染器（select/switch/checkbox/input）
│       └── PipelinePreview.vue  Pipeline Override JSON 预览
└── Readme.md
```

## 移动端 UI 布局

```
┌─────────────────────────┐
│ 🎮 Demo Program 3   🌐  │  Header (项目名 + 语言切换)
├─────────────────────────┤
│ Ctrl [Android ▼]        │  Toolbar (控制器 + 资源选择)
│ Res  [Official ▼]       │
├─────────────────────────┤
│ TASKS                   │
│ ┌─────────────────────┐ │
│ │☑ 收取荒原            │ │  任务卡片（折叠）
│ └─────────────────────┘ │
│ ┌─────────────────────┐ │
│ │☑ 常规作战        ▼ │ │  任务卡片（展开）
│ │ ┌─────────────────┐ │ │
│ │ │TASK 选择关卡     │ │ │  ← select 下拉
│ │ │[3-9 厄险...  ▼] │ │ │
│ │ │  └RES 使用理智 ⚪🔵│ │ │  ← switch 开关 + 递归子项
│ │ │  └TASK 刷完操作 ▼│ │ │
│ │ │TASK 复现次数     │ │ │
│ │ │[x3 ▼]            │ │ │
│ │ │TASK 战斗划火柴   │ │ │
│ │ │[普通] [蓄力] [连续]│ │ │  ← checkbox 多选
│ │ └─────────────────┘ │ │
│ │ ▸ Pipeline Override │ │  ← 可折叠 JSON 预览
│ └─────────────────────┘ │
│ ┌─────────────────────┐ │
│ │☐ 活动任务 (灰色)     │ │  不支持当前资源/控制器
│ └─────────────────────┘ │
│                         │
│ ⚙️ Global Settings (3)  │  全局/资源/控制器级选项（折叠）
│                         │
│ PRESETS                 │
│ [📋 刷日常] [🔥 ALL IN]  │  预设（横向滚动）
├─────────────────────────┤
│    ▶ Start (3 tasks)    │  底部固定启动按钮
└─────────────────────────┘
```

## 展示的协议特性

| 特性 | 说明 |
|------|------|
| `select` 单选下拉 | 全宽下拉框 |
| `switch` 二值开关 | 右对齐滑动开关 (Yes/No) |
| `checkbox` 多选 | 等宽 Toggle 按钮组，按 cases 定义顺序合并 |
| `input` 用户输入 | 垂直堆叠输入框 + 正则校验 + `pipeline_type` 标注 |
| 递归子配置项 | `case.option` → 无限嵌套渲染（左侧缩进线） |
| Controller 级联过滤 | 切换控制器 → 自动过滤可用资源包 |
| Resource 级联过滤 | 不支持的任务灰色禁用 |
| Option 适用性过滤 | `option.controller` / `option.resource` 约束 |
| `global_option` | 全局选项（折叠区，紫色标记） |
| `resource.option` | 资源级选项（蓝色标记） |
| `controller.option` | 控制器级选项（琥珀色标记） |
| `task.option` | 任务级选项（手风琴展开内联） |
| Option 覆盖顺序 | `global → resource → controller → task` 递归合并 |
| `preset` 预设 | 横向滚动预设按钮，一键应用 |
| `pipeline_override` 预览 | `<details>` 折叠面板，语法高亮 JSON |
| i18n 国际化 | 中英文切换 |
| `default_check` / `default_case` | 默认值初始化 |
