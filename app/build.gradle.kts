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

val piSourceDir: String? = (localProperties.getProperty("pi.sourceDir")
    ?: System.getenv("PI_SOURCE_DIR"))?.takeIf { it.isNotBlank() }

val piAssetsDir: Provider<Directory> = layout.buildDirectory.dir("generated/piAssets")


val agentSourceDir: String? = (localProperties.getProperty("agent.sourceDir")
    ?: System.getenv("AGENT_SOURCE_DIR"))?.takeIf { it.isNotBlank() }

val agentAbiPatterns: List<String> =
    (localProperties.getProperty("agent.abi") ?: "").split(',').map(String::trim)
        .filter(String::isNotEmpty).ifEmpty { listOf("*") }

val agentAssetsDir: Provider<Directory> = layout.buildDirectory.dir("generated/agentAssets")
val agentJniLibsDir: Provider<Directory> = layout.buildDirectory.dir("generated/agentJniLibs")

val emptyAgentSource: File =
    layout.buildDirectory.dir("generated/agentEmptySource").get().asFile.apply { mkdirs() }

val shippedAbis: List<String> = listOf("arm64-v8a", "x86_64")

val debugAbiFilters: List<String> =
    (localProperties.getProperty("build.debugAbi") ?: "").split(',').map(String::trim)
        .filter(String::isNotEmpty).ifEmpty { shippedAbis }


val piIncludePatterns: List<String> = listOf(
    "interface.json",
    "tasks/**",
    "resource/**",
    "resource_*/**",
    "data/**",
    "locales/**",
    "CONTACT",
    "LICENSE",
) + (localProperties.getProperty("pi.includeExtra") ?: "").split(',').map(String::trim)
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
        val entries = root.walkTopDown().filter { it.isFile }
            .map { it.toRelativeString(root).replace('\\', '/') }.sorted().toList()
        manifestFile.get().asFile.writeText(entries.joinToString("\n"))
    }
}

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
        fingerprintFile.get().asFile.writeText(
            digest.digest().joinToString("") { "%02x".format(it) })
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
            useLegacyPackaging = true
        }
        resources {
            pickFirsts += setOf(
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
            )
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath =
                System.getenv("KEYSTORE_PATH") ?: localProperties.getProperty("KEYSTORE_PATH", "")
            if (keystorePath.isNotEmpty()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                    ?: localProperties.getProperty("KEYSTORE_PASSWORD", "")
                keyAlias =
                    System.getenv("KEY_ALIAS") ?: localProperties.getProperty("KEY_ALIAS", "")
                keyPassword =
                    System.getenv("KEY_PASSWORD") ?: localProperties.getProperty("KEY_PASSWORD", "")
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
                abiFilters += shippedAbis
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            val keystorePath =
                System.getenv("KEYSTORE_PATH") ?: localProperties.getProperty("KEYSTORE_PATH", "")
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
        // 归档内部已经 deflate 过，再压一遍白烧构建时间；STORED 之后运行时是直读
        noCompress += "zip"
    }
}

tasks.named("preBuild") {
    dependsOn(writePiManifest, writeAgentIndex, syncAgentJniLibs)
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory(piAssetsDir.get().asFile.absolutePath)
        variant.sources.assets?.addStaticSourceDirectory(agentAssetsDir.get().asFile.absolutePath)
        variant.sources.jniLibs?.addStaticSourceDirectory(agentJniLibsDir.get().asFile.absolutePath)
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    inputs.files(piAssetsDir.map { it.asFileTree }).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(agentAssetsDir.map { it.asFileTree }).withPathSensitivity(PathSensitivity.RELATIVE)
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    inputs.files(agentJniLibsDir.map { it.asFileTree })
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(project(":hidden-api"))

    implementation(project(":annotation-api"))
    ksp(project(":ksp-processor"))
    implementation(project(":semi-icons"))

    // MIUI 上系统权限页的跳转差异大，自己拼 Intent 覆盖不全
    implementation(libs.xx.permissions)

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
    // Android 没有自带的 mail 实现，三个一起才跑得起 Transport.send
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
