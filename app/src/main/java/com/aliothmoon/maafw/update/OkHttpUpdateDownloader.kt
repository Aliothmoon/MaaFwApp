package com.aliothmoon.maafw.update

import com.aliothmoon.maafw.MaaDispatchers
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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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

            val request = Request.Builder()
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
                }
                .get()
                .build()
            client.newCall(request).await().use { response ->
                if (!response.code.isSuccess()) {
                    throw UpdateDownloadException(
                        UpdateDownloadFailure.HTTP,
                        "Update download returned HTTP ${response.code}",
                    )
                }
                val body = response.body
                val declaredLength = body.contentLength()
                val digest = MessageDigest.getInstance(SHA_256)
                var downloadedBytes = 0L
                var lastProgressBytes: Long = -PROGRESS_INTERVAL.toLong()

                onProgress(0L, declaredLength)
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = body.byteStream().read(buffer)
                        if (read < 0) break
                        if (read == 0) continue

                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloadedBytes += read
                        if (
                            downloadedBytes - lastProgressBytes >= PROGRESS_INTERVAL ||
                            downloadedBytes == declaredLength
                        ) {
                            onProgress(downloadedBytes, declaredLength)
                            lastProgressBytes = downloadedBytes
                        }
                    }
                    output.flush()
                }
                if (declaredLength >= 0 && downloadedBytes != declaredLength) {
                    throw UpdateDownloadException(
                        UpdateDownloadFailure.NETWORK,
                        "Update download ended at $downloadedBytes of $declaredLength bytes",
                    )
                }

                val actualDigest = hex(digest.digest())
                if (!MessageDigest.isEqual(
                        actualDigest.toByteArray(Charsets.US_ASCII),
                        expectedDigest.toByteArray(Charsets.US_ASCII),
                    )
                ) {
                    throw UpdateDownloadException(
                        UpdateDownloadFailure.DIGEST_MISMATCH,
                        "SHA-256 mismatch: expected $expectedDigest, got $actualDigest",
                    )
                }
                if (downloadedBytes != lastProgressBytes) {
                    onProgress(downloadedBytes, declaredLength)
                }
                move(part, target)
                return downloaded(update, target, actualDigest)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UpdateDownloadException) {
            return failed(e.failure, e.message)
        } catch (_: SecurityException) {
            return failed(UpdateDownloadFailure.STORAGE, "Update download storage access was denied")
        } catch (_: IOException) {
            return failed(UpdateDownloadFailure.NETWORK, "Update download failed")
        } catch (e: Exception) {
            return failed(UpdateDownloadFailure.UNKNOWN, e.message)
        } finally {
            part.delete()
        }
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
