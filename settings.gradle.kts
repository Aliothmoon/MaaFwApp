pluginManagement {
    // 构建约定插件（maafw.*）在这个独立构建里，模块脚本只按 id 应用
    includeBuild("build-logic")
    repositories {
        mavenLocal()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven { url = uri("https://jitpack.io") }
        mavenCentral()
    }
}

rootProject.name = "MaaFwApp"
include(":app")
include(":hidden-api")
// Preferences DataStore 的 schema 代码生成（@PrefSchema / @PrefKey）
include(":annotation-api")
include(":ksp-processor")
// Semi Design 图标（vector drawable + SemiIconRes）
include(":semi-icons")
// 真机性能测量；只有 benchmark 变体，跑的是 .benchmark 后缀那个包
include(":macrobenchmark")
// 端到端测试的靶子：横屏输入框页，主 app 在真机上用临时 PI 驱动它，验 InputText 等控制链路
include(":testapp")
