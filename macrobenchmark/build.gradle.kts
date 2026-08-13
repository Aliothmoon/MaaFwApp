plugins {
    id("maafw.android.benchmark")
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
