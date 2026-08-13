package com.aliothmoon.maafw.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** Plugin code has no libs accessor like build scripts do, the catalog comes from the extension */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.library(alias: String) = findLibrary(alias).orElseThrow {
    IllegalStateException("no $alias in the version catalog")
}
