// 隐藏系统 API 的编译期存根：特权进程要按这些接口反射/直调系统服务，
// 而 android.jar 不导出它们。只被 compileOnly 依赖，不进 APK
plugins {
    // AGP 9 已把 library 插件带上 classpath，再带版本号会冲突
    id("com.android.library")
}

android {
    namespace = "com.aliothmoon.hiddenapi"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
