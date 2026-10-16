plugins {
    alias(libs.plugins.spotless)
    alias(libs.plugins.android.application) apply false
    // 声明 kotlin-jvm 是为了把 KGP 2.3.21 钉在共享构建 classpath 上；
    // 不声明的话 AGP 9 的内置 Kotlin 会用它自带的版本，与 :app 编出的元数据对不上
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
}

spotless {
    val excludedPaths = listOf(
        "**/build/**",
        ".gradle/**",
        ".kotlin/**",
        ".maafw/**",
        ".worktree/**"
    )
    val ktlintEditorConfigOverride = mapOf(
        "ktlint_standard_backing-property-naming" to "disabled",
        "ktlint_standard_filename" to "disabled",
        "ktlint_standard_function-naming" to "disabled",
        "ktlint_standard_kdoc" to "disabled",
        "ktlint_standard_max-line-length" to "disabled",
        "ktlint_standard_property-naming" to "disabled",
        "ktlint_standard_value-parameter-comment" to "disabled"
    )

    kotlin {
        ktlint("1.8.0")
            .editorConfigOverride(ktlintEditorConfigOverride)
        target("**/*.kt")
        targetExclude(*excludedPaths.toTypedArray())
    }
    kotlinGradle {
        ktlint("1.8.0")
            .editorConfigOverride(ktlintEditorConfigOverride)
        target("**/*.gradle.kts")
        targetExclude(*excludedPaths.toTypedArray())
    }
}
