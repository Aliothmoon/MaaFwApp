# 资源接入

MaaFwApp 本身不包含业务资源。资源开发者写好 Project Interface 之后，用一份打包配方告诉本仓库「资源在哪、包名叫什么、agent 怎么拉起」，再出 APK。

资源在**构建期**打进 APK。换资源就要重新出包，不能在设备上换一份 `interface.json` 接着用。

开发资源前请先读 [Project Interface V2 协议](https://github.com/MaaXYZ/MaaFramework/blob/main/docs/zh_cn/3.3-ProjectInterfaceV2%E5%8D%8F%E8%AE%AE.md)。本应用面向发布后的配置与运行；写 Pipeline、对识别请用 MaaFramework 提供的调试工具。

配方字段的完整注释见 [`pi-profile.sample.yaml`](pi-profile.sample.yaml)。

## 环境

- JDK 17、Android SDK
- Python 3（下载 MaaFramework 产物、组装 Python agent）
- 一份 `interface_version` 为 `2` 的资源项目

MaaFramework 的 `.so` 不在本仓库里，需要先铺：

```bash
python scripts/setup_maa_framework.py              # latest
python scripts/setup_maa_framework.py --tag v5.x.x
python scripts/setup_maa_framework.py --abi arm64-v8a
```

## 打包配方

把 `pi-profile.sample.yaml` 拷到仓库外改好，然后：

```properties
# local.properties（不进 git）
pi.profile=D:/path/to/your-profile.yaml
```

或设环境变量 `PI_PROFILE`。相对路径相对**配方文件自己所在的目录**。字符串里可以用 `${VAR}` / `${VAR:-默认值}`；没有回落又没设变量时构建会失败，避免打出包名残缺的包。

最小例子：

```yaml
assets: D:/path/to/your-pi

app:
  id: yourpi          # 拼成 com.aliothmoon.maafw.yourpi，只收小写包段
  label: Your PI
  icon: logo.png      # 只收 png / webp
```

`app` 整段可省略，此时包名是 `com.aliothmoon.maafw`，图标用仓库自带的。

`include` 缺省为 `interface.json`、`tasks/**`、`resource/**`、`resource_*/**`、`data/**`、`locales/**`、`CONTACT`、`LICENSE`。写了 `include` 就完全以配方为准。`exclude` 叠在上面，用来挖掉大而不用的文件。`.git` / `node_modules` / `.venv` / `__pycache__` 构建期一定会去掉。

图标文件必须落在白名单里，否则进不了包，界面上不会显示。agent 脚本如果放在资源仓库的 `agent/` 下，把 `agent/**` 加进 `include`。

## 资源约定

按桌面端习惯写即可，下面几条是 Android 上会对不上的地方：

| 桌面端 | 在 MaaFwApp 里                                                      |
|:---|:------------------------------------------------------------------|
| 把 GUI 放到资源目录旁 | 构建期打进 APK，换资源重新出包                                                 |
| `controller` 写 Adb / Win32 / PlayCover… | 只取 `type: Adb` 的一项，设备上由 native controller 实现，**不必为 Android 另写一条** |
| `child_exec` / `child_args` 决定怎么起 agent | 这两项不参与实际命令行；可执行体和参数写在配方的 `agent.runtimes`                         |
| 应用名、图标来自 PI 顶层 `label` / `icon` | 由配方的 `app.*` 决定 |
| Mirror酱 | 暂不支持 |
| 热更新资源 | 资源随 APK 绑定，换资源重新出包 |
| 调试期改完资源刷新即可 | 重装或清数据。版本号跟本仓库提交走，只换资源不改本仓库时设备可能继续用旧解包 |

`welcome`、`description`、`contact`、`license`、`github`、`telemetry` 会进首启弹窗和设置页「关于」。正文支持 `$i18n`、相对文件、URL 或直接文本。

## Agent

资源的 `interface.json` 里声明了 `agent` 才需要这一节。没声明就把配方里的 `agent:` 整段删掉。

声明了却没带运行时，开始任务时会明确失败，不会默默跳过。

载荷（`agent/main.py` 等）跟资源走，靠 `include` 进包。解释器或编译好的 ELF 走配方的 `agent.sourceDir`。怎么启动写在 `agent.runtimes`，条数必须和 PI 的 `agent[]` 相同、按顺序一一对应。

```text
<sourceDir>/
└── <abi>/                 arm64-v8a 或 x86_64
    ├── jniLibs/           单文件 ELF，文件名必须是 lib*.so
    └── bundle/            解释器目录树
```

```yaml
agent:
  sourceDir: D:/path/to/agent-dist
  abi: [arm64-v8a]
  runtimes:
    - location: bundle
      executable: bin/python3
      args: [-u, agent/main.py]
      env:
        PYTHONHOME: "{bundle}/prefix"
        PYTHONPATH: "{bundle}/site-packages/pure.zip:{bundle}/site-packages"
        LD_LIBRARY_PATH: "{bundle}/prefix/lib:{nativeLibs}"
        MAAFW_BINARY_PATH: "{nativeLibs}"
```

进程实际收到的命令是：

```text
<executable>  args...  <identifier>
```

identifier 一定在最后一位，工作目录是资源解包根。`{bundle}` 和 `{nativeLibs}` 是仅有的两个占位符。

agent 侧：读最后一个参数当 identifier，注册自定义识别 / 动作，然后 `MaaAgentServerStartUp` → `MaaAgentServerJoin`。多个 agent 同时在线时回调名不要重复。

### Python

用仓库脚本组运行时，不要自己拼一套 CPython。内核从 [MaaAgentCoreAndroid](https://github.com/Aliothmoon/MaaAgentCoreAndroid) 下载，本地只叠资源项目的依赖，不需要 NDK：

```bash
python scripts/build_agent_bundle.py \
    --out <配方里的 agent.sourceDir> \
    --requirements <资源项目>/requirements.txt
```

`requirements.txt` 若是 lock（`uv export` / `pip freeze`），加上 `--no-deps`。用了 Pillow 等带原生库的包时，Android 轮子在 Chaquopy 索引上，版本往往比桌面旧：

```bash
python scripts/build_agent_bundle.py \
    --out <agent.sourceDir> \
    --requirements <资源项目>/requirements.txt \
    --exclude pillow --require pillow==11.0.0 \
    --extra-index-url https://chaquo.com/pypi-13.1/
```

脚本如果提示存在 Chaquopy native 库，把 `{bundle}/site-packages/chaquopy/lib` 加进 `LD_LIBRARY_PATH`。

### 编译型（C++ / Go / Rust）

编成单文件 ELF，命名为 `lib*.so`，放到 `<sourceDir>/<abi>/jniLibs/`。`location` 用 `nativeLibs`，`LD_LIBRARY_PATH` 设为 `{nativeLibs}`。头文件从 `.maa-cache` 里那份 MaaFramework 解，版本要和铺进去的 `.so` 一致。

## 出包

```bash
./gradlew :app:installDebug        # 已连接设备；Windows 用 .\gradlew.bat
./gradlew :app:assembleRelease
```

本地迭代可以在 `local.properties` 里写 `build.debugAbi=arm64-v8a`，debug 包就只打这一个 ABI。release 始终包含 `arm64-v8a` 和 `x86_64`。

改完配方或上游资源后，也可以只跑同步：

```bash
./gradlew :app:syncPiAssets
./gradlew :app:packAgentBundles :app:syncAgentJniLibs :app:writeAgentIndex
```

## 装上之后

1. 授予通知、电池白名单（保活前台服务要用）。
2. 选择 Shizuku 或 root，授权并等到服务连上。
3. 第一次打开会解包资源；有 bundle 型 agent 时，运行时解到可执行目录。
4. 选服务器、核对任务、开始。

## 常见问题

| 现象 | 原因 |
|:---|:---|
| 能安装，一点开始就加载 native 失败 | 没跑 `setup_maa_framework.py`，或 ABI 不对 |
| 构建直接报 `agent.sourceDir` / `runtimes` | 两个必须一起写；没有 agent 就把整段删掉 |
| `runtimes` 条数对不上 | 必须和 PI 的 `agent[]` 按序一一对应 |
| 开始任务提示未带 agent 运行时 | PI 声明了 agent，配方没配 |
| 图标不显示 | 文件没进 `include`，或不是 png / webp |
| 换了资源重装，设备还是旧内容 | 只换资源、没改本仓库时版本号不变，清应用数据或再交一次提交 |

查看运行日志：

```bash
adb logcat -s MaaFw
```
