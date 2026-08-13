// 隐藏系统 API 的编译期存根：特权进程要按这些接口反射/直调系统服务，
// 而 android.jar 不导出它们。只被 compileOnly 依赖，不进 APK
plugins {
    id("maafw.android.library")
}

android {
    namespace = "com.aliothmoon.hiddenapi"
}
