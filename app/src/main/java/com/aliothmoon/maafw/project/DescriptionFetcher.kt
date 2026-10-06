package com.aliothmoon.maafw.project

import android.content.Context
import com.aliothmoon.maafw.MaaDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File

/** URL 形态 description 的拉取器：OkHttp + ETag 磁盘缓存 */
class DescriptionFetcher private constructor(context: Context) : RemoteTextFetcher {

    private val client = OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "pi_description_http"), CACHE_SIZE_BYTES))
        .build()

    /** 失败时回落返回原始 URL 文本 */
    suspend fun fetch(url: String): String = fetchOrNull(url) ?: url

    override suspend fun fetchOrNull(url: String): String? = withContext(MaaDispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("Failed to fetch description: HTTP %d for %s", response.code, url)
                    return@withContext null
                }
                response.body.string()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Failed to fetch description: %s", url)
            null
        }
    }

    companion object {
        private const val CACHE_SIZE_BYTES = 5L * 1024 * 1024

        @Volatile
        private var instance: DescriptionFetcher? = null

        fun get(context: Context): DescriptionFetcher =
            instance ?: synchronized(this) {
                instance ?: DescriptionFetcher(context.applicationContext).also { instance = it }
            }
    }
}
