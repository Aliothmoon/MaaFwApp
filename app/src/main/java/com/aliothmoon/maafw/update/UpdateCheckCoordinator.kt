package com.aliothmoon.maafw.update

import timber.log.Timber

internal class UpdateCheckCoordinator(
    checkers: Collection<UpdateSourceChecker>,
) : UpdateCheckApi {

    private val checkersBySource = checkers.associateBy(UpdateSourceChecker::source)

    override suspend fun check(request: UpdateCheckRequest): UpdateCheckResult {
        Timber.tag("UpdateCheck").w(
            "preferred=%s alternative=%s currentVersion=%s channel=%s rid=%s repo=%s",
            request.preferredSource, request.alternativeSource,
            request.currentVersion, request.channel,
            request.mirrorChyanRid, request.githubRepository,
        )
        val alternativeSource = request.alternativeSource
            ?.takeIf { it != request.preferredSource }
        val checker = checkersBySource[request.preferredSource] ?: run {
            Timber.tag("UpdateCheck").w(
                "no checker registered for preferred=%s",
                request.preferredSource,
            )
            return UpdateCheckResult.SourceFailed(
                source = request.preferredSource,
                reason = UpdateCheckFailure.MISSING_CONFIGURATION,
                alternativeSource = alternativeSource,
                message = "Update source checker is not registered",
            )
        }

        return when (val result = checker.check(request)) {
            is SourceCheckResult.UpdateAvailable -> {
                Timber.tag("UpdateCheck").w(
                    "source=%s UPDATE_AVAILABLE version=%s",
                    checker.source, result.version,
                )
                result.result(checker.source)
            }
            is SourceCheckResult.UpToDate -> {
                Timber.tag("UpdateCheck").w(
                    "source=%s UP_TO_DATE latest=%s",
                    checker.source, result.latestVersion,
                )
                crossCheckOnUpToDate(checker.source, alternativeSource, result, request)
            }
            is SourceCheckResult.Failed -> {
                Timber.tag("UpdateCheck").w(
                    "source=%s FAILED reason=%s msg=%s alternative=%s canFallback=%s",
                    checker.source, result.reason, result.message,
                    alternativeSource, result.reason.canFallback,
                )
                val altChecker = alternativeSource?.let(checkersBySource::get)
                if (altChecker != null && result.reason.canFallback) {
                    Timber.tag("UpdateCheck").w(
                        "falling back: preferred=%s reason=%s -> alternative=%s",
                        checker.source, result.reason, alternativeSource,
                    )
                    check(request.copy(preferredSource = alternativeSource, alternativeSource = null))
                } else {
                    result.result(checker.source, alternativeSource)
                }
            }
        }
    }

    /**
     * Preferred 报 UpToDate 时也交叉查 alternative：发现 UpdateAvailable 则采纳覆盖 preferred，
     * 否则保留 preferred 的 UpToDate（alt 报 Failed 或 UpToDate 都视为"未发现更新"）。
     */
    private suspend fun crossCheckOnUpToDate(
        preferredSource: UpdateSource,
        alternativeSource: UpdateSource?,
        primary: SourceCheckResult.UpToDate,
        request: UpdateCheckRequest,
    ): UpdateCheckResult {
        val altChecker = alternativeSource?.let(checkersBySource::get)
        if (altChecker == null) {
            Timber.tag("UpdateCheck").w(
                "source=%s UP_TO_DATE; no alternative checker registered, keeping latest=%s",
                preferredSource, primary.latestVersion,
            )
            return primary.result(preferredSource)
        }
        Timber.tag("UpdateCheck").w(
            "source=%s UP_TO_DATE latest=%s; cross-checking %s",
            preferredSource, primary.latestVersion, alternativeSource,
        )
        val alt = altChecker.check(
            request.copy(preferredSource = alternativeSource, alternativeSource = null),
        )
        return when (alt) {
            is SourceCheckResult.UpdateAvailable -> {
                Timber.tag("UpdateCheck").w(
                    "cross-check %s UPDATE_AVAILABLE version=%s (preferred was UP_TO_DATE); using alternative",
                    alternativeSource, alt.version,
                )
                alt.result(alternativeSource)
            }
            is SourceCheckResult.UpToDate -> {
                Timber.tag("UpdateCheck").w(
                    "cross-check %s UP_TO_DATE latest=%s; keeping preferred",
                    alternativeSource, alt.latestVersion,
                )
                primary.result(preferredSource)
            }
            is SourceCheckResult.Failed -> {
                Timber.tag("UpdateCheck").w(
                    "cross-check %s FAILED reason=%s msg=%s; keeping preferred",
                    alternativeSource, alt.reason, alt.message,
                )
                primary.result(preferredSource)
            }
        }
    }

    private fun SourceCheckResult.UpdateAvailable.result(
        source: UpdateSource,
    ): UpdateCheckResult.UpdateAvailable = UpdateCheckResult.UpdateAvailable(
        source = source,
        version = version,
        downloadUrl = downloadUrl,
        sha256 = sha256,
        releaseNotesUrl = releaseNotesUrl,
        releaseNotes = releaseNotes,
    )

    private fun SourceCheckResult.UpToDate.result(
        source: UpdateSource,
    ): UpdateCheckResult.UpToDate = UpdateCheckResult.UpToDate(
        source = source,
        latestVersion = latestVersion,
    )

    private fun SourceCheckResult.Failed.result(
        source: UpdateSource,
        alternativeSource: UpdateSource?,
    ): UpdateCheckResult.SourceFailed = UpdateCheckResult.SourceFailed(
        source = source,
        reason = reason,
        alternativeSource = alternativeSource,
        message = message,
    )

    /**
     * MISSING_CONFIGURATION: 源缺配置时改由另一个源兜底
     * RESOURCE_NOT_FOUND / NO_MATCHING_ASSET: 源说"这条资源没有这条更新"，让另一个源试试
     */
    private val UpdateCheckFailure.canFallback: Boolean
        get() = this == UpdateCheckFailure.RESOURCE_NOT_FOUND ||
                this == UpdateCheckFailure.NO_MATCHING_ASSET ||
                this == UpdateCheckFailure.MISSING_CONFIGURATION
}
