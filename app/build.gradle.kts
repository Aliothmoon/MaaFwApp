import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.screenshot)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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

// 指纹判断已解包的 PI 是否过期；清单让解包按行直取，免去逐层 AssetManager.list()（每层都是 native 调用）
// 不用 BuildConfig：两者都要等 syncPiAssets 执行完才算得出，而 BuildConfig 的值在 configuration 阶段就得定
val writePiIndex = tasks.register("writePiIndex") {
    group = "build"
    description = "算出同步后 PI 的内容指纹与解包清单，落成 assets/pi.fingerprint 与 assets/pi.manifest"
    dependsOn(syncPiAssets)
    val piDir = piAssetsDir.map { it.dir("pi") }
    val fingerprintFile = piAssetsDir.map { it.file("pi.fingerprint") }
    val manifestFile = piAssetsDir.map { it.file("pi.manifest") }
    inputs.dir(piDir).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(fingerprintFile)
    outputs.file(manifestFile)
    doLast {
        val root = piDir.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        val entries = root.walkTopDown()
            .filter { it.isFile }
            .map { it.toRelativeString(root).replace('\\', '/') }
            .sorted()
            .toList()
        entries.forEach { entry ->
            // 路径一并入摘要：只比内容会漏掉纯改名
            digest.update(entry.toByteArray())
            File(root, entry).inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }
        fingerprintFile.get().asFile.writeText(digest.digest().joinToString("") { "%02x".format(it) })
        // 一行一条相对路径，运行时按行读，app 侧不必解析 JSON
        manifestFile.get().asFile.writeText(entries.joinToString("\n"))
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

    experimentalProperties["android.experimental.enableScreenshotTest"] = true
}

tasks.named("preBuild") {
    dependsOn(writePiIndex)
}

// AGP 9 不再接受 Provider 形式的 sourceSet srcDir，只能走 Variant API
// 选静态目录而非 generated：各 variant 共享同一份产物，避免 debug/release 各拷一遍
// 代价是不自动携带 task 依赖，由上面的 preBuild dependsOn 兜住
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory(piAssetsDir.get().asFile.absolutePath)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 只在编译期解析隐藏系统 API，运行时由系统提供
    compileOnly(project(":hidden-api"))

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

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    screenshotTestImplementation(libs.compose.screenshot.validation)
    screenshotTestImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
