plugins {
    id("maafw.android.application")
    id("maafw.android.compose")
    id("maafw.pi.assets")
    id("maafw.agent.runtime")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.aliothmoon.maafw"

    defaultConfig {
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

    buildFeatures {
        buildConfig = true
        aidl = true
    }

    androidResources {
        noCompress += "zip"
    }

    packaging {
        jniLibs {
            // 必须保持 true（= manifest 里 extractNativeLibs=true），这是本应用的功能前提：
            // 特权进程是靠执行 nativeLibraryDir/liblauncher.so 拉起来的
            // （ProcessServiceConnectorBackend.buildStartCommand：「test -x <launcher> || exit 126」），
            // agent 的 exec 文件同样走这个目录。设成 false 时 .so 不会被解压成可执行文件，
            // 服务进程永远回不了 binder，表现为「connect timeout after 15000ms (backend=SHIZUKU)」。
            // 官方发行包同样是 extractNativeLibs=true。
            // 代价：.so 压缩存储，Android 15+ 会对可调试版弹「16 KB 对齐」提示（仅提示，不影响运行）。
            useLegacyPackaging = true
        }
    }
}

baselineProfile {
    // 落进 src/main 而不是 src/release：测量用的 benchmark 是另一个 build type，
    // 吃不到 src/release 的源集，不合并的话量出来会是「profile 毫无效果」
    mergeIntoMain = true
}

dependencies {
    compileOnly(project(":hidden-api"))

    implementation(project(":annotation-api"))
    ksp(project(":ksp-processor"))
    implementation(project(":semi-icons"))
    // Baseline Profile 的生产者；采集用例在那个模块里
    baselineProfile(project(":macrobenchmark"))

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
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.window)
    // 自定义背景选图时按 EXIF 方向摆正
    implementation(libs.androidx.exifinterface)
    // 运行通知走小米超级岛时拼焦点通知模板
    implementation(libs.xzakota.focus.api)
    // 解包与项目加载各圈一段，Perfetto / macrobenchmark 里才归得了因
    implementation(libs.androidx.tracing.ktx)
    // Baseline Profile 在 API 33 以下靠它在启动时装入
    implementation(libs.androidx.profileinstaller)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    implementation(libs.timber)
    implementation(libs.reorderable)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.html)
    implementation(libs.markwon.image)
    implementation(libs.markwon.linkify)
    implementation(libs.markwon.strikethrough)
    implementation(libs.okhttp)
    implementation(libs.semver)
    implementation(libs.sentry.android.core)
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
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    // Perfetto 里把 slice 命名到 composable；只进 debug，release 不带
    debugImplementation(libs.androidx.compose.runtime.tracing)
    debugImplementation(libs.androidx.tracing.perfetto.binary)
}
