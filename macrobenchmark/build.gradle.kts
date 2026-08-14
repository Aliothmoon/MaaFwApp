plugins {
    id("maafw.android.benchmark")
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.aliothmoon.maafw.macrobenchmark"
    targetProjectPath = ":app"
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.uiautomator)
}
