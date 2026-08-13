plugins {
    id("maafw.kotlin.jvm")
}

dependencies {
    implementation(project(":annotation-api"))
    implementation(libs.symbol.processing.api)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)
}
