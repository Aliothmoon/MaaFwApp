package com.aliothmoon.maafw.update

/** Update metadata sources. The API never silently switches between them. */
enum class UpdateSource {
    MIRROR_CHYAN,
    GITHUB,
}

enum class UpdateChannel {
    STABLE,
    BETA,
}

enum class AndroidAbi(val mirrorArch: String) {
    ARM64("arm64"),
    X86_64("amd64"),
    ARM("arm"),
    X86("386"),
}

enum class UpdateCheckFailure {
    MISSING_CONFIGURATION,
    NETWORK,
    HTTP,
    RATE_LIMITED,
    RESOURCE_NOT_FOUND,
    INVALID_RESPONSE,
    NO_MATCHING_ASSET,
    VERSION_INVALID,
    UNKNOWN,
}

data class UpdateCheckRequest(
    val currentVersion: String,
    val preferredSource: UpdateSource = UpdateSource.MIRROR_CHYAN,
    val alternativeSource: UpdateSource? = UpdateSource.GITHUB,
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val abi: AndroidAbi = AndroidAbi.ARM64,
    val mirrorChyanRid: String? = null,
    val githubRepository: String? = null,
    val githubToken: String? = null,
)

sealed interface UpdateCheckResult {
    data class UpdateAvailable(
        val source: UpdateSource,
        val version: String,
        val downloadUrl: String,
        val sha256: String?,
        val releaseNotesUrl: String?,
        val releaseNotes: String?,
    ) : UpdateCheckResult

    data class UpToDate(
        val source: UpdateSource,
        val latestVersion: String,
    ) : UpdateCheckResult

    data class SourceFailed(
        val source: UpdateSource,
        val reason: UpdateCheckFailure,
        val alternativeSource: UpdateSource? = null,
        val message: String? = null,
    ) : UpdateCheckResult
}

interface UpdateCheckApi {
    suspend fun check(request: UpdateCheckRequest): UpdateCheckResult
}

/** A provider's view of the result; the orchestrator adds source-switching metadata. */
internal sealed interface SourceCheckResult {
    data class UpdateAvailable(
        val version: String,
        val downloadUrl: String,
        val sha256: String?,
        val releaseNotesUrl: String?,
        val releaseNotes: String?,
    ) : SourceCheckResult

    data class UpToDate(val latestVersion: String) : SourceCheckResult

    data class Failed(
        val reason: UpdateCheckFailure,
        val message: String? = null,
    ) : SourceCheckResult
}

internal interface UpdateSourceChecker {
    val source: UpdateSource

    suspend fun check(request: UpdateCheckRequest): SourceCheckResult
}

internal class UpdateSourceException(
    val reason: UpdateCheckFailure,
    override val message: String?,
    override val cause: Throwable? = null,
) : Exception(message, cause)
