import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

val gitVersionCode: Int by lazy {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()
}

val gitVersionName: String by lazy {
    val desc = providers.exec {
        commandLine("git", "describe", "--tags", "--always")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
    val match = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-(\d+)-g[0-9a-f]+)?$""").matchEntire(desc)
    if (match != null) {
        val (major, minor, patch, distance) = match.destructured
        if (distance.isEmpty()) "$major.$minor.$patch"
        else "$major.$minor.${patch.toInt() + 1}-alpha.$distance"
    } else {
        desc.removePrefix("v").ifEmpty { "0.0.0-dev" }
    }
}

// PI 内容不进仓库：由 pi.sourceDir 指向的外部 PI 项目在构建期同步进 assets
// 换一个 PI 只改这处配置并重新出包，代码与仓库都不感知具体项目
val piSourceDir: String? = (localProperties.getProperty("pi.sourceDir")
    ?: System.getenv("PI_SOURCE_DIR"))?.takeIf { it.isNotBlank() }

val piAssetsDir: Provider<Directory> = layout.buildDirectory.dir("generated/piAssets")

// agent 运行时同样不进仓库：解释器或 ELF 由 agent.sourceDir 指向的外部目录在构建期同步进来
// 布局 <dir>/agent-runtime.json + <dir>/<abi>/{jniLibs,bundle}/
//   jniLibs/ 随 APK 装进 nativeLibraryDir（文件名须是 lib*.so，否则装机时不解压），零解包
//   bundle/  随 assets 走，首启由特权进程解包到可执行目录（解释器这类目录树只能走这条）
// 不配置就是不带 agent，与 pi.sourceDir 缺失同样软失败
val agentSourceDir: String? = (localProperties.getProperty("agent.sourceDir")
    ?: System.getenv("AGENT_SOURCE_DIR"))?.takeIf { it.isNotBlank() }

// 生成目录跨 variant 共享，没法按 buildType 分 ABI；本地只带一份运行时靠这个键
// local.properties: agent.abi=arm64-v8a
val agentAbiPatterns: List<String> = (localProperties.getProperty("agent.abi") ?: "")
    .split(',')
    .map(String::trim)
    .filter(String::isNotEmpty)
    .ifEmpty { listOf("*") }

val agentAssetsDir: Provider<Directory> = layout.buildDirectory.dir("generated/agentAssets")
val agentJniLibsDir: Provider<Directory> = layout.buildDirectory.dir("generated/agentJniLibs")

/** 未配置 agent.sourceDir 时给 Sync 用的空源；只为让它照常执行并清空目标目录 */
val emptyAgentSource: File =
    layout.buildDirectory.dir("generated/agentEmptySource").get().asFile.apply { mkdirs() }

// 发布要覆盖的 ABI；jniLibs 里就这两份
val shippedAbis: List<String> = listOf("arm64-v8a", "x86_64")

// 本地迭代只编一个 ABI 能省掉一半 CMake 与打包时间；release 不受影响
// local.properties: build.debugAbi=arm64-v8a
val debugAbiFilters: List<String> = (localProperties.getProperty("build.debugAbi") ?: "")
    .split(',')
    .map(String::trim)
    .filter(String::isNotEmpty)
    .ifEmpty { shippedAbis }

// 只对可枚举的顶层项做白名单，命中的目录整体拷贝
// 不用黑名单：PI 协议允许 description 与图片引用子目录里的任意 md 和资源，
// 按扩展名通配排除会误伤 announcement、locales 等目录下被引用的正文
val piIncludePatterns: List<String> = listOf(
    "interface.json",
    "tasks/**",
    "resource/**",
    "resource_*/**",
    "data/**",
    "locales/**",
    "CONTACT",
    "LICENSE",
) + (localProperties.getProperty("pi.includeExtra") ?: "")
    .split(',')
    .map(String::trim)
    .filter(String::isNotEmpty)

val syncPiAssets = tasks.register<Sync>("syncPiAssets") {
    group = "build"
    description = "把 pi.sourceDir 指向的 PI 项目同步为 assets/pi"
    // 与 PI_ASSET_ROOT 对应：srcDir 挂在上一级，APK 内路径才是 assets/pi
    into(piAssetsDir.map { it.dir("pi") })
    if (piSourceDir != null) {
        from(piSourceDir) {
            piIncludePatterns.forEach { include(it) }
            // 白名单已能排除它们，显式剪枝只为免去遍历上游仓库的 .git 等大目录
            exclude(".git/**", "node_modules/**", ".venv/**", "__pycache__/**")
        }
    } else {
        // 软失败：单元测试用 src/test/fixtures，不该被 PI 配置阻塞
        // 缺 PI 的包在运行时自然落到 ProjectState.Error
        doFirst {
            logger.warn("未配置 pi.sourceDir（local.properties）或 PI_SOURCE_DIR，构建产物将不含 PI")
        }
    }
}

// 清单让解包按行直取，免去逐层 AssetManager.list()（每层都是 native 调用）
// 不塞进 BuildConfig：清单要等 syncPiAssets 执行完才列得出，而 BuildConfig 的值在 configuration 阶段就得定
// 已解包的 PI 是否过期改由 versionCode 判定（PiInstaller），构建期不再算内容指纹
val writePiManifest = tasks.register("writePiManifest") {
    group = "build"
    description = "列出同步后 PI 的解包清单，落成 assets/pi.manifest"
    dependsOn(syncPiAssets)
    val piDir = piAssetsDir.map { it.dir("pi") }
    val manifestFile = piAssetsDir.map { it.file("pi.manifest") }
    inputs.dir(piDir).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(manifestFile)
    doLast {
        val root = piDir.get().asFile
        val entries = root.walkTopDown()
            .filter { it.isFile }
            .map { it.toRelativeString(root).replace('\\', '/') }
            .sorted()
            .toList()
        // 一行一条相对路径，运行时按行读，app 侧不必解析 JSON
        manifestFile.get().asFile.writeText(entries.joinToString("\n"))
    }
}

// bundle 打成单个 zip 再进 assets，不散装铺开
// AAPT 会按默认规则改写 assets：`<dir>_*` 整目录丢掉（Python 的 `_pyrepl/` 首当其冲）、
// `.*` 丢掉、`.gz` 解压后改名。实测 1554 个条目进包只剩 1272 个。归档之后它碰不到里面的名字，
// 顺带省掉运行时在上百 MB 的 APK 里做上千次条目查找
val packAgentBundles = tasks.register<Zip>("packAgentBundles") {
    group = "build"
    description = "把 agent.sourceDir 的 bundle 按 ABI 打包进 assets/agent"
    destinationDirectory.set(agentAssetsDir.map { it.dir("agent") })
    archiveFileName.set("bundle.zip")
    if (agentSourceDir != null) {
        agentAbiPatterns.forEach { abi ->
            from(agentSourceDir) {
                include("$abi/bundle/**")
                // <abi>/bundle/** 拍平成 <abi>/**：bundle 只是源目录里的分类，落到设备上没有意义
                eachFile { path = path.replaceFirst("/bundle/", "/") }
                includeEmptyDirs = false
            }
        }
    }
}

val syncAgentAssets = tasks.register<Sync>("syncAgentAssets") {
    group = "build"
    description = "把 agent.sourceDir 的运行时描述同步为 assets/agent"
    dependsOn(packAgentBundles)
    // 索引文件要落在这层之外：Sync 会清掉目标目录里不属于源的东西
    into(agentAssetsDir.map { it.dir("agent") })
    // bundle.zip 由 packAgentBundles 写进同一个目录，别被 Sync 当成多余内容删掉
    preserve { include("bundle.zip") }
    if (agentSourceDir != null) {
        from(agentSourceDir) { include("agent-runtime.json") }
    } else {
        // 给一个空源目录而不是什么都不给：Sync 无源时判 NO-SOURCE 整个跳过，
        // 上一次配置过 agent.sourceDir 留下的运行时会一直留在生成目录里，混进后来的包
        from(emptyAgentSource)
        doFirst { logger.info("未配置 agent.sourceDir，构建产物将不含 agent 运行时") }
    }
}

val syncAgentJniLibs = tasks.register<Sync>("syncAgentJniLibs") {
    group = "build"
    description = "把 agent.sourceDir 的单文件可执行体同步进 jniLibs"
    into(agentJniLibsDir)
    if (agentSourceDir != null) {
        from(agentSourceDir) {
            agentAbiPatterns.forEach { include("$it/jniLibs/**") }
            eachFile { path = path.replaceFirst("/jniLibs/", "/") }
            includeEmptyDirs = false
        }
    } else {
        from(emptyAgentSource)
    }
}

// 指纹判断已解包的运行时是否过期；归档自带目录，不再需要单独的解包清单
// 描述文件不进指纹——它不影响解包出来的字节，改描述不该触发重解一棵上百 MB 的树
val writeAgentIndex = tasks.register("writeAgentIndex") {
    group = "build"
    description = "算出 agent 运行时归档的内容指纹，落成 assets/agent.fingerprint"
    dependsOn(syncAgentAssets)
    val bundleZip = agentAssetsDir.map { it.file("agent/bundle.zip") }
    val fingerprintFile = agentAssetsDir.map { it.file("agent.fingerprint") }
    // 用 files 而不是 inputs.file：没配 agent.sourceDir 时归档可能不存在，inputs.file 会直接判校验失败
    inputs.files(bundleZip).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(fingerprintFile)
    doLast {
        fingerprintFile.get().asFile.parentFile.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        bundleZip.get().asFile.takeIf { it.isFile }?.inputStream()?.use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        fingerprintFile.get().asFile.writeText(digest.digest().joinToString("") { "%02x".format(it) })
    }
}

android {
    namespace = "com.aliothmoon.maafw"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aliothmoon.maafw"
        minSdk = 28
        targetSdk = 36
        versionCode = gitVersionCode
        versionName = gitVersionName
        println("Build version: versionCode=$versionCode, versionName=$versionName")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                // launcher 是 C，bridge 是 C++；两者各自的编译选项写在 CMakeLists 里
                // 静态链接 libc++：进程里已有 MaaFramework 自带的 libc++_shared.so，
                // 再让 bridge 依赖一份本地 NDK 编的会变成同进程两套 libc++
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/native/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // 必须解压成真实文件：launcher 要被 execv 执行，
            // MaaFramework 又靠 dladdr 取自身 .so 所在目录再 dlopen 兄弟库（MaaUtils 的 library_dir()）
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
                ?: localProperties.getProperty("KEYSTORE_PATH", "")
            if (keystorePath.isNotEmpty()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                    ?: localProperties.getProperty("KEYSTORE_PASSWORD", "")
                keyAlias = System.getenv("KEY_ALIAS")
                    ?: localProperties.getProperty("KEY_ALIAS", "")
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?: localProperties.getProperty("KEY_PASSWORD", "")
            }
        }
    }

    buildTypes {
        debug {
            ndk {
                abiFilters += debugAbiFilters
            }
        }
        release {
            ndk {
                // 发布始终两个 ABI 都打，不受 build.debugAbi 影响
                abiFilters += shippedAbis
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val keystorePath = System.getenv("KEYSTORE_PATH")
                ?: localProperties.getProperty("KEYSTORE_PATH", "")
            if (keystorePath.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
        // 特权进程的服务面走 AIDL，AGP 默认不开
        aidl = true
    }

    androidResources {
        // agent 运行时归档内部已经 deflate 过，APK 里再压一遍只是白烧构建时间；
        // 存成 STORED 之后运行时读它是直读，不必先把整条目 inflate 出来
        noCompress += "zip"
    }
}

tasks.named("preBuild") {
    dependsOn(writePiManifest, writeAgentIndex, syncAgentJniLibs)
}

// AGP 9 不再接受 Provider 形式的 sourceSet srcDir，只能走 Variant API
// 选静态目录而非 generated：各 variant 共享同一份产物，避免 debug/release 各拷一遍
// 代价是不自动携带 task 依赖，由上面的 preBuild dependsOn 兜住
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory(piAssetsDir.get().asFile.absolutePath)
        variant.sources.assets?.addStaticSourceDirectory(agentAssetsDir.get().asFile.absolutePath)
        variant.sources.jniLibs?.addStaticSourceDirectory(agentJniLibsDir.get().asFile.absolutePath)
    }
}

// addStaticSourceDirectory 只让合并任务在执行时读到目录，不把它登记成输入：
// 实测改了 PI 或 agent 运行时之后 mergeXxxAssets 仍判 UP-TO-DATE，APK 里留着上一次的内容
// 这里补一条内容级输入把 up-to-date 判定接上；用 fileTree 而非 inputs.dir，目录缺失时才不会校验失败
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    inputs.files(piAssetsDir.map { it.asFileTree }).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(agentAssetsDir.map { it.asFileTree }).withPathSensitivity(PathSensitivity.RELATIVE)
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    inputs.files(agentJniLibsDir.map { it.asFileTree }).withPathSensitivity(PathSensitivity.RELATIVE)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 只在编译期解析隐藏系统 API，运行时由系统提供
    compileOnly(project(":hidden-api"))

    // AppSettings 的 Preferences key 与读写代码由 KSP 从 @PrefSchema 生成
    implementation(project(":annotation-api"))
    ksp(project(":ksp-processor"))

    // Semi Design 图标（vector + SemiIconRes）
    implementation(project(":semi-icons"))

    // OEM 权限适配（MIUI 上系统权限页跳转差异大，自己拼 Intent 覆盖不全）
    implementation(libs.xx.permissions)

    // 提权与 native 接入
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.libsu)
    // aar 里带 libjnidispatch.so，用 jar 会在设备上找不到 native 分发库
    implementation(libs.jna) { artifact { type = "aar" } }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.window)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    implementation(libs.timber)
    implementation(libs.reorderable)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.markwon.core)
    implementation(libs.markwon.html)
    implementation(libs.markwon.image)
    implementation(libs.markwon.linkify)
    implementation(libs.markwon.strikethrough)
    implementation(libs.okhttp)
    // SMTP 推送渠道；Android 平台没有自带的 mail 实现，三个一起才跑得起 Transport.send
    implementation(libs.angus.mail)
    implementation(libs.angus.activation)
    implementation(libs.jakarta.activation.api)
    // 前台模式控制层：拖拽/吸边/多屏/返回键拦截都在库里，自己写这几样是纯坑区
    implementation(libs.floatingx)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
