package com.aliothmoon.maafw.remote.internal

/** `start_app` 的目标包信息文案；格式化独立出来，便于 JVM 单测 */
object TargetAppInfoLog {

    @JvmStatic
    fun success(
        rawSpec: String,
        packageName: String,
        versionName: String?,
        versionCode: Int?,
        uid: Int?,
        displayId: Int,
        forceStop: Boolean,
    ): String {
        val version = formatVersion(versionName, versionCode)
        val uidText = uid?.toString() ?: "unknown"
        return "Target app info: spec=$rawSpec package=$packageName version=$version " +
            "uid=$uidText displayId=$displayId forceStop=$forceStop"
    }

    @JvmStatic
    fun failure(
        rawSpec: String,
        packageName: String,
        error: Throwable?,
        displayId: Int,
        forceStop: Boolean,
    ): String = "Target app info unavailable: spec=$rawSpec package=$packageName " +
        "error=${error?.javaClass?.simpleName ?: "unknown"} displayId=$displayId forceStop=$forceStop"

    private fun formatVersion(versionName: String?, versionCode: Int?): String =
        when {
            versionName != null && versionCode != null -> "$versionName ($versionCode)"
            versionName != null -> "$versionName (unknown code)"
            versionCode != null -> "unknown name ($versionCode)"
            else -> "unknown"
        }
}
