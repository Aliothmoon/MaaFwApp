package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.domain.ProjectMetadata
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/** 拉 URL 形态的正文；失败返回 null，由调用方决定怎么降级 */
fun interface RemoteTextFetcher {
    suspend fun fetchOrNull(url: String): String?
}

/** 待展示的公告正文，与决定「看没看过」的指纹 */
data class ResolvedWelcome(
    val bodies: List<String>,
    val fingerprint: String,
)

/**
 * URL 形态的 welcome 先拉正文再算指纹，对齐 MXU：只看声明的话 URL 不变，
 * 远端公告改了多少次都不会再弹
 *
 * 非 URL 的项仍用物化前的原始声明，切语言换了译文不重弹，也不让已看过的用户升级后再弹一次
 */
class WelcomeResolver(private val fetcher: RemoteTextFetcher) {

    /** 没有 welcome，或任一 URL 拉取失败，都返回 null：失败时不弹也不记指纹，下次加载再试 */
    suspend fun resolve(metadata: ProjectMetadata, version: String?): ResolvedWelcome? {
        if (metadata.welcome.isEmpty()) return null
        // 声明与物化结果常是同一个 URL，一轮内只拉一次
        val fetched = mutableMapOf<String, String>()
        suspend fun bodyOf(text: String): String? {
            if (!isRemoteUrl(text)) return text
            return fetched[text] ?: fetcher.fetchOrNull(text)?.also { fetched[text] = it }
        }

        val bodies = metadata.welcome.map { bodyOf(it) ?: return null }
        val declarations = metadata.welcomeDeclarations.map { bodyOf(it) ?: return null }
        return ResolvedWelcome(bodies, welcomeFingerprint(declarations, version))
    }
}

/**
 * 单条沿用数组支持之前的算法：看过的用户升级后不重弹，`"x"` 改写成 `["x"]` 也不算内容变化。
 * 多条按有序原文整体算，增删、重排、改任一条都会重弹
 */
internal fun welcomeFingerprint(declarations: List<String>, version: String?): String {
    val declaration = declarations.singleOrNull() ?: JsonArray(declarations.map(::JsonPrimitive)).toString()
    return MessageDigest.getInstance("SHA-256")
        .digest("$declaration@${version.orEmpty()}".toByteArray())
        .take(8)
        .joinToString("") { "%02x".format(it) }
}
