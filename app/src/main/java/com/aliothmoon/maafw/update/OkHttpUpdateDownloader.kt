package com.aliothmoon.maafw.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import com.aliothmoon.maafw.MaaDispatchers
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class OkHttpUpdateDownloader(
    private val directory: File,
    okHttpClient: OkHttpClient = defaultClient(),
    private val userAgent: String = "MaaFwApp Android",
) : UpdateDownloadApi {

    private val client = okHttpClient.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val mutex = Mutex()

    override suspend fun download(
        update: UpdateCheckResult.UpdateAvailable,
        credentials: UpdateDownloadCredentials,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): UpdateDownloadResult = mutex.withLock {
        withContext(MaaDispatchers.IO) {
            downloadLocked(update, credentials, onProgress)
        }
    }

    private suspend fun downloadLocked(
        update: UpdateCheckResult.UpdateAvailable,
        credentials: UpdateDownloadCredentials,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): UpdateDownloadResult {
        val url = try {
            update.downloadUrl.toHttpUrl()
        } catch (_: IllegalArgumentException) {
            return failed(UpdateDownloadFailure.INVALID_URL, "Invalid update download URL")
        }
        if (!url.isHttps) {
            return failed(UpdateDownloadFailure.INVALID_URL, "Update download must use HTTPS")
        }

        val expectedDigest = normalizeDigest(update.sha256)
            ?: return failed(
                UpdateDownloadFailure.INVALID_DIGEST,
                "Update SHA-256 digest is missing or invalid",
            )
        val target = targetFile(update, expectedDigest)
        val part = File(target.path + PART_EXTENSION)
        try {
            if (target.isFile && digestOf(target) == expectedDigest) {
                return downloaded(update, target, expectedDigest)
            }
            if (!prepareDirectory()) {
                return failed(UpdateDownloadFailure.STORAGE, "Cannot create update download directory")
            }
            if (target.isFile && !target.delete()) {
                return failed(UpdateDownloadFailure.STORAGE, "Cannot replace stale update APK")
            }

            // 断点续传:上一次失败的 .part 字节数,会作为 Range 起点
            val resumeOffset = part.length()
            val request = buildRequest(url, update, credentials, resumeOffset)
            client.newCall(request).await().use { response ->
                val code = response.code
                if (code != HTTP_OK && code != HTTP_PARTIAL) {
                    throw UpdateDownloadException(
                        UpdateDownloadFailure.HTTP,
                        "Update download returned HTTP $code",
                    )
                }
                val partial = code == HTTP_PARTIAL
                // 服务器不认 Range:重置 .part,从 0 重新下载
                val effectiveOffset = if (partial) {
                    resumeOffset
                } else if (resumeOffset > 0L) {
                    Timber.tag("UpdateDownload").w(
                        "Server returned HTTP %d despite Range header; dropping stale %d-byte .part",
                        code, resumeOffset,
                    )
                    if (!part.delete()) {
                        throw UpdateDownloadException(
                            UpdateDownloadFailure.STORAGE,
                            "Cannot truncate stale partial update download",
                        )
                    }
                    0L
                } else 0L

                val body = response.body
                val responseLength = body.contentLength()
                val totalLength = if (effectiveOffset > 0L) {
                    if (responseLength < 0L) -1L else responseLength + effectiveOffset
                } else responseLength

                val digest = MessageDigest.getInstance(SHA_256)
                // 续传时把已落盘的字节先增量喂给 digest,避免最终验证时再扫一遍整个文件
                if (effectiveOffset > 0L) {
                    FileInputStream(part).buffered().use { ins ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val n = ins.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                        }
                    }
                }

                var downloadedBytes = effectiveOffset
                var lastProgressBytes: Long = -PROGRESS_INTERVAL.toLong()
                onProgress(downloadedBytes, totalLength)

                RandomAccessFile(part, "rw").use { raf ->
                    if (effectiveOffset > 0L) raf.seek(raf.length())
                    else raf.setLength(0L)
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = body.byteStream().read(buffer)
                        if (read < 0) break
                        if (read == 0) continue

                        raf.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloadedBytes += read
                        if (
                            downloadedBytes - lastProgressBytes >= PROGRESS_INTERVAL ||
                            downloadedBytes == totalLength
                        ) {
                            onProgress(downloadedBytes, totalLength)
                            lastProgressBytes = downloadedBytes
                        }
                    }
                }

                if (totalLength >= 0 && downloadedBytes != totalLength) {
                    throw UpdateDownloadException(
                        UpdateDownloadFailure.NETWORK,
                        "Update download ended at $downloadedBytes of $totalLength bytes",
                    )
                }

                val actualDigest = hex(digest.digest())
                if (!MessageDigest.isEqual(
                        actualDigest.toByteArray(Charsets.US_ASCII),
                        expectedDigest.toByteArray(Charsets.US_ASCII),
                    )
                ) {
                    // digest 不匹配:.part 的字节是坏的,留它就会污染下次下载
                    part.delete()
                    throw UpdateDownloadException(
                        UpdateDownloadFailure.DIGEST_MISMATCH,
                        "SHA-256 mismatch: expected $expectedDigest, got $actualDigest",
                    )
                }
                if (downloadedBytes != lastProgressBytes) {
                    onProgress(downloadedBytes, totalLength)
                }
                move(part, target)
                return downloaded(update, target, actualDigest)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UpdateDownloadException) {
            // 失败时保留 .part,下次重试时通过 Range 头从断点继续
            return failed(e.failure, e.message)
        } catch (_: SecurityException) {
            return failed(UpdateDownloadFailure.STORAGE, "Update download storage access was denied")
        } catch (_: IOException) {
            return failed(UpdateDownloadFailure.NETWORK, "Update download failed")
        } catch (e: Exception) {
            return failed(UpdateDownloadFailure.UNKNOWN, e.message)
        }
        // 显式不提 finally { part.delete() }: 成功路径 move() 已经改名,失败路径故意保留 .part
    }

    private fun buildRequest(
        url: okhttp3.HttpUrl,
        update: UpdateCheckResult.UpdateAvailable,
        credentials: UpdateDownloadCredentials,
        resumeOffset: Long,
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .apply {
                header("User-Agent", userAgent)
                when (update.source) {
                    UpdateSource.GITHUB -> credentials.githubToken
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?.let { header("Authorization", "Bearer $it") }
                    // Mirror酱的 CDK 用于解析下载地址，不作为 CDN 下载请求的鉴权头。
                    UpdateSource.MIRROR_CHYAN -> Unit
                }
                if (resumeOffset > 0L) {
                    header("Range", "bytes=$resumeOffset-")
                    Timber.tag("UpdateDownload").w(
                        "Resuming download from offset %d (existing .part)",
                        resumeOffset,
                    )
                }
            }
            .get()
        return builder.build()
    }

    private fun prepareDirectory(): Boolean = try {
        directory.mkdirs() || directory.isDirectory
    } catch (_: SecurityException) {
        false
    }

    private fun move(source: File, target: File) {
        try {
            try {
                Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                if (!source.renameTo(target)) {
                    throw UpdateDownloadException(
                        UpdateDownloadFailure.STORAGE,
                        "Cannot finalize update APK",
                    )
                }
            }
        } catch (e: SecurityException) {
            throw UpdateDownloadException(
                UpdateDownloadFailure.STORAGE,
                "Cannot finalize update APK",
                e,
            )
        } catch (e: IOException) {
            throw UpdateDownloadException(
                UpdateDownloadFailure.STORAGE,
                "Cannot finalize update APK",
                e,
            )
        }
    }

    private fun targetFile(
        update: UpdateCheckResult.UpdateAvailable,
        expectedDigest: String,
    ): File {
        val identity = expectedDigest.takeLast(DIGEST_FILE_SUFFIX_LENGTH)
        val safeVersion = update.version.replace(UNSAFE_FILE_NAME, "_")
            .take(MAX_VERSION_LENGTH)
            .ifBlank { "unknown" }
        return File(directory, "maafw-${safeVersion}-${identity}.apk")
    }

    private fun digestOf(file: File): String = try {
        file.inputStream().use { input ->
            val digest = MessageDigest.getInstance(SHA_256)
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            hex(digest.digest())
        }
    } catch (e: IOException) {
        throw UpdateDownloadException(
            UpdateDownloadFailure.STORAGE,
            "Cannot verify downloaded update APK",
            e,
        )
    }

    private fun normalizeDigest(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        val digest = value.replaceFirst(DIGEST_PREFIX_PATTERN, "").lowercase(Locale.US)
        return digest.takeIf(DIGEST_PATTERN::matches)
    }

    private fun downloaded(
        update: UpdateCheckResult.UpdateAvailable,
        file: File,
        sha256: String,
    ): UpdateDownloadResult.Downloaded = UpdateDownloadResult.Downloaded(
        DownloadedUpdate(
            source = update.source,
            version = update.version,
            file = file,
            sha256 = sha256,
        ),
    )

    private fun failed(
        reason: UpdateDownloadFailure,
        message: String? = null,
    ): UpdateDownloadResult.Failed = UpdateDownloadResult.Failed(reason, message)

    private fun hex(value: ByteArray): String = value.joinToString("") {
        String.format(Locale.US, "%02x", it)
    }

    private fun Int.isSuccess(): Boolean = this in 200..299

    private class UpdateDownloadException(
        val failure: UpdateDownloadFailure,
        override val message: String,
        override val cause: Throwable? = null,
    ) : Exception(message, cause)

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val PART_EXTENSION = ".part"
        const val PROGRESS_INTERVAL = 1024 * 1024
        const val SHA_256 = "SHA-256"
        const val DIGEST_FILE_SUFFIX_LENGTH = 16
        const val MAX_VERSION_LENGTH = 48
        const val HTTP_OK = 200
        const val HTTP_PARTIAL = 206
        val DIGEST_PREFIX_PATTERN = Regex("""^sha256:""", RegexOption.IGNORE_CASE)
        val DIGEST_PATTERN = Regex("""^[0-9a-f]{64}$""")
        val UNSAFE_FILE_NAME = Regex("""[^A-Za-z0-9._-]""")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, _, _ -> response.close() }
        }
    })
    continuation.invokeOnCancellation { cancel() }
}
