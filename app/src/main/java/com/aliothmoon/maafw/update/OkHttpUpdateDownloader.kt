package com.aliothmoon.maafw.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

class OkHttpUpdateDownloader(
    private val directory: File,
    okHttpClient: OkHttpClient = defaultClient(),
    private val userAgent: String = "MaaFwApp Android",
) {

    private val client = okHttpClient.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val mutex = Mutex()

    suspend fun download(
        update: ResolvedUpdate,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): UpdateDownloadResult = mutex.withLock {
        withContext(MaaDispatchers.IO) {
            downloadLocked(update, onProgress)
        }
    }

    private suspend fun downloadLocked(
        update: ResolvedUpdate,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): UpdateDownloadResult {
        val url = try {
            update.downloadUrl.toHttpUrl()
        } catch (_: IllegalArgumentException) {
            return failed(UpdateDownloadFailure.INVALID_URL)
        }
        if (!url.isHttps) {
            return failed(UpdateDownloadFailure.INVALID_URL)
        }

        val expectedDigest = normalizeDigest(update.sha256)
            ?: return failed(UpdateDownloadFailure.INVALID_DIGEST)
        val target = targetFile(update, expectedDigest)
        val part = File(target.path + PART_EXTENSION)
        try {
            if (target.isFile && digestOf(target) == expectedDigest) {
                return downloaded(update, target, expectedDigest)
            }
            if (!prepareDirectory()) {
                return failed(UpdateDownloadFailure.STORAGE)
            }
            if (target.isFile && !target.delete()) {
                return failed(UpdateDownloadFailure.STORAGE)
            }

            client.newCall(buildRequest(url)).await().use { response ->
                val code = response.code
                if (code != HTTP_OK) {
                    throw UpdateDownloadException(
                        UpdateDownloadFailure.HTTP,
                        "HTTP $code",
                        detail = uiTextOf(R.string.update_detail_http_status, code),
                    )
                }
                val totalLength = response.body.contentLength()
                val digest = MessageDigest.getInstance(SHA_256)
                var downloadedBytes = 0L
                var lastProgressBytes = -PROGRESS_INTERVAL.toLong()
                onProgress(0L, totalLength)

                val input = response.body.byteStream()
                FileOutputStream(part).use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        out.write(buffer, 0, read)
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
                        "ended at $downloadedBytes of $totalLength bytes",
                        detail = uiTextOf(
                            R.string.update_download_detail_bytes,
                            downloadedBytes.toString(),
                            totalLength.toString(),
                        ),
                    )
                }

                val actualDigest = hex(digest.digest())
                if (actualDigest != expectedDigest) {
                    throw UpdateDownloadException(
                        UpdateDownloadFailure.DIGEST_MISMATCH,
                        "SHA-256 mismatch: expected $expectedDigest, got $actualDigest",
                        detail = uiTextOf(
                            R.string.update_download_detail_digest,
                            expectedDigest,
                            actualDigest,
                        ),
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
            return failed(e.failure, e.detail, e.message)
        } catch (_: SecurityException) {
            return failed(UpdateDownloadFailure.STORAGE)
        } catch (_: IOException) {
            return failed(UpdateDownloadFailure.NETWORK)
        } catch (e: Exception) {
            return failed(UpdateDownloadFailure.UNKNOWN, logMessage = e.message)
        } finally {
            // 不支持续传：成功路径 move 后 part 已不存在，失败/取消一律清掉
            part.delete()
        }
    }

    private fun buildRequest(url: okhttp3.HttpUrl): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Encoding", "identity")
            .get()
            .build()

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
                cause = e,
            )
        } catch (e: IOException) {
            throw UpdateDownloadException(
                UpdateDownloadFailure.STORAGE,
                "Cannot finalize update APK",
                cause = e,
            )
        }
    }

    private fun targetFile(
        update: ResolvedUpdate,
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
            cause = e,
        )
    }

    private fun normalizeDigest(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        val digest = value.replaceFirst(DIGEST_PREFIX_PATTERN, "").lowercase(Locale.US)
        return digest.takeIf(DIGEST_PATTERN::matches)
    }

    private fun downloaded(
        update: ResolvedUpdate,
        file: File,
        sha256: String,
    ): UpdateDownloadResult.Downloaded = UpdateDownloadResult.Downloaded(
        DownloadedUpdate(
            version = update.version,
            file = file,
            sha256 = sha256,
        ),
    )

    private fun failed(
        reason: UpdateDownloadFailure,
        detail: UiText? = null,
        logMessage: String? = null,
    ): UpdateDownloadResult.Failed {
        if (logMessage != null) Timber.tag("UpdateDownload").w("%s: %s", reason.name, logMessage)
        return UpdateDownloadResult.Failed(reason, detail)
    }

    private fun hex(value: ByteArray): String = value.joinToString("") {
        String.format(Locale.US, "%02x", it)
    }

    private class UpdateDownloadException(
        val failure: UpdateDownloadFailure,
        override val message: String,
        val detail: UiText? = null,
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
        val DIGEST_PREFIX_PATTERN = Regex("""^sha256:""", RegexOption.IGNORE_CASE)
        val DIGEST_PATTERN = Regex("""^[0-9a-f]{64}$""")
        val UNSAFE_FILE_NAME = Regex("""[^A-Za-z0-9._-]""")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
