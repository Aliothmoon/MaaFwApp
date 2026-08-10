plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.screenshot) apply false
    // 声明 kotlin-jvm 是为了把 KGP 2.3.21 钉在共享构建 classpath 上；
    // 不声明的话 AGP 9 的内置 Kotlin 会用它自带的版本，与 :app 编出的元数据对不上
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
