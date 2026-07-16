# MaaFwApp 移动端 Web 原型

`proto` 是 Android 客户端的 Vue 3 移动端交互原型，用于快速验证任务配置、动态选项、运行状态与视觉规范。

## 数据来源

原型不再维护一套独立的虚构任务数据。构建时会直接读取并合并：

```text
app/src/main/assets/sample/tasks/**/*.json
```

合并规则与 Android `ProjectLoader` 保持一致：

- `task[]` 形成项目任务目录；
- `option{}` 形成动态选项定义；
- `preset[]` 形成初始运行配置；
- 重名任务、选项和 preset 保留按路径排序后首次出现的定义。

资源名称与 Android `ProjectLoader.RESOURCE_DISPLAY_NAMES` 对齐，Controller 固定为 Android / ADB。当前 sample 没有 `global_option`、`resource.option` 或 `controller.option`，因此原型只展示任务级选项。

## 当前模拟内容

- 4 个真实 sample 配置：日常-长草、日常-活动、日常-复刻、各种小游戏；
- 25 个真实任务和对应任务分组；
- 67 个真实动态选项，包括 select、switch、checkbox、input 和递归子选项；
- 当前配置的有序任务队列、任务目录添加、启用状态、删除操作和任务级选项值；
- 资源适用性、ADB Controller 适用性和 Pipeline Override 预览；
- 开始、停止、运行进度与日志反馈。

preset 只用于初始化或重置当前队列。删除任务不会修改项目任务目录，也不会修改原始 preset。

## 运行

```bash
cd proto
pnpm install
pnpm dev
pnpm test
pnpm build
```

开发服务器默认运行在 `http://localhost:5173`。

## 主要文件

```text
proto/
├── DESIGN.md                         移动端视觉规范
├── src/data.js                       合并 Android sample PI 分片
├── src/composables/useInterface.js   原型状态与交互逻辑
├── src/utils/pipeline.js              typed placeholder 递归替换
├── src/components/BottomSheet.vue     共用移动端底部抽屉
├── src/components/TaskList.vue       当前配置任务队列
├── src/components/TaskCatalogDrawer.vue 真实任务目录
├── src/components/OptionDrawer.vue   任务选项底部抽屉
├── src/components/OptionRenderer.vue 动态选项递归渲染
└── src/components/PipelinePreview.vue Pipeline Override 预览
```
