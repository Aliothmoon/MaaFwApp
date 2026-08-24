package com.aliothmoon.maafw.update

internal class UpdateCheckCoordinator(
    checkers: Collection<UpdateSourceChecker>,
) : UpdateCheckApi {

    private val checkersBySource = checkers.associateBy(UpdateSourceChecker::source)

    override suspend fun check(request: UpdateCheckRequest): UpdateCheckResult {
        val checker = checkersBySource[request.preferredSource]
            ?: return UpdateCheckResult.SourceFailed(
                source = request.preferredSource,
                reason = UpdateCheckFailure.MISSING_CONFIGURATION,
                alternativeSource = request.alternativeSource.takeIf {
                    it != null && it != request.preferredSource
                },
                message = "Update source checker is not registered",
            )

        return when (val result = checker.check(request)) {
            is SourceCheckResult.UpdateAvailable -> UpdateCheckResult.UpdateAvailable(
                source = checker.source,
                version = result.version,
                downloadUrl = result.downloadUrl,
                sha256 = result.sha256,
                releaseNotesUrl = result.releaseNotesUrl,
                releaseNotes = result.releaseNotes,
            )
            is SourceCheckResult.UpToDate -> UpdateCheckResult.UpToDate(
                source = checker.source,
                latestVersion = result.latestVersion,
            )
            is SourceCheckResult.Failed -> UpdateCheckResult.SourceFailed(
                source = checker.source,
                reason = result.reason,
                alternativeSource = request.alternativeSource?.takeIf {
                    it != request.preferredSource
                },
                message = result.message,
            )
        }
    }
}
