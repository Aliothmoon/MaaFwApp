package com.aliothmoon.maafw.update

import timber.log.Timber

/**
 * 更新编排：检查（多源兜底 + UpToDate 交叉查）与下载地址解析（单源）
 *
 * 检查永远是匿名、不带 preferred/alternative 之外的源切换；解析只用用户选定的那一个源，
 * 「API never silently switches between sources」
 */
class UpdateService(
    clients: Collection<UpdateSourceClient>,
) {

    private val clientsBySource = clients.associateBy(UpdateSourceClient::source)

    suspend fun checkUpdate(
        request: UpdateCheckRequest,
        preferred: UpdateSource = UpdateSource.MIRRORCHYAN,
        alternative: UpdateSource? = UpdateSource.GITHUB,
    ): UpdateCheckResult {
        Timber.tag("UpdateCheck").w(
            "preferred=%s alternative=%s currentVersion=%s channel=%s rid=%s repo=%s",
            preferred, alternative,
            request.currentVersion, request.channel,
            request.mirrorchyanRid, request.githubRepository,
        )
        val alternativeSource = alternative?.takeIf { it != preferred }
        val checker = clientsBySource[preferred] ?: run {
            Timber.tag("UpdateCheck").w("no client registered for preferred=%s", preferred)
            return UpdateCheckResult.SourceFailed(
                source = preferred,
                reason = UpdateCheckFailure.MISSING_CONFIGURATION,
            )
        }

        return when (val result = checker.check(request)) {
            is UpdateCheckResult.UpdateAvailable -> {
                Timber.tag("UpdateCheck").w(
                    "source=%s UPDATE_AVAILABLE version=%s",
                    checker.source, result.info.version,
                )
                result
            }

            is UpdateCheckResult.UpToDate -> {
                Timber.tag("UpdateCheck").w("source=%s UP_TO_DATE latest=%s", checker.source, result.latestVersion)
                crossCheckOnUpToDate(checker.source, alternativeSource, result, request)
            }

            is UpdateCheckResult.SourceFailed -> {
                Timber.tag("UpdateCheck").w(
                    "source=%s FAILED reason=%s alternative=%s canFallback=%s",
                    checker.source, result.reason,
                    alternativeSource, result.reason.canFallback,
                )
                if (alternativeSource != null && result.reason.canFallback) {
                    Timber.tag("UpdateCheck").w(
                        "falling back: preferred=%s reason=%s -> alternative=%s",
                        checker.source, result.reason, alternativeSource,
                    )
                    checkUpdate(request, preferred = alternativeSource, alternative = null)
                } else {
                    result
                }
            }
        }
    }

    suspend fun resolveDownload(request: UpdateResolveRequest): UpdateResolveResult {
        val client = clientsBySource[request.source]
            ?: return UpdateResolveResult.Failed(request.source, UpdateCheckFailure.MISSING_CONFIGURATION)
        return client.resolve(request)
    }

    /**
     * preferred 报 UpToDate 时也交叉查 alternative：发现 UpdateAvailable 则采纳覆盖 preferred，
     * 否则保留 preferred 的 UpToDate（alt 报 Failed 或 UpToDate 都视为「未发现更新」）
     */
    private suspend fun crossCheckOnUpToDate(
        preferredSource: UpdateSource,
        alternativeSource: UpdateSource?,
        primary: UpdateCheckResult.UpToDate,
        request: UpdateCheckRequest,
    ): UpdateCheckResult {
        val altChecker = alternativeSource?.let(clientsBySource::get)
        if (altChecker == null) {
            Timber.tag("UpdateCheck").w(
                "source=%s UP_TO_DATE; no alternative checker registered, keeping latest=%s",
                preferredSource, primary.latestVersion,
            )
            return primary
        }
        Timber.tag("UpdateCheck").w(
            "source=%s UP_TO_DATE latest=%s; cross-checking %s",
            preferredSource, primary.latestVersion, alternativeSource,
        )
        return when (val alt = altChecker.check(request)) {
            is UpdateCheckResult.UpdateAvailable -> {
                Timber.tag("UpdateCheck").w(
                    "cross-check %s UPDATE_AVAILABLE version=%s (preferred was UP_TO_DATE); using alternative",
                    alternativeSource, alt.info.version,
                )
                alt
            }

            is UpdateCheckResult.UpToDate -> {
                Timber.tag("UpdateCheck").w(
                    "cross-check %s UP_TO_DATE latest=%s; keeping preferred",
                    alternativeSource, alt.latestVersion,
                )
                primary
            }

            is UpdateCheckResult.SourceFailed -> {
                Timber.tag("UpdateCheck").w(
                    "cross-check %s FAILED reason=%s; keeping preferred",
                    alternativeSource, alt.reason,
                )
                primary
            }
        }
    }

    /**
     * MISSING_CONFIGURATION: 源缺配置时改由另一个源兜底
     * RESOURCE_NOT_FOUND / NO_MATCHING_ASSET: 源说"这条资源没有这条更新"，让另一个源试试
     */
    private val UpdateCheckFailure.canFallback: Boolean
        get() = this == UpdateCheckFailure.RESOURCE_NOT_FOUND ||
                this == UpdateCheckFailure.NO_MATCHING_ASSET ||
                this == UpdateCheckFailure.MISSING_CONFIGURATION
}
