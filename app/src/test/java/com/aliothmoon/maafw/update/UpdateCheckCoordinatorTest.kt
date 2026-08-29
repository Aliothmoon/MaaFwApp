package com.aliothmoon.maafw.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckCoordinatorTest {

    @Test
    fun `mirror missing package falls back to github`() = runBlocking {
        val mirror = FakeChecker(
            UpdateSource.MIRROR_CHYAN,
            SourceCheckResult.Failed(UpdateCheckFailure.RESOURCE_NOT_FOUND),
        )
        val github = FakeChecker(
            UpdateSource.GITHUB,
            SourceCheckResult.UpdateAvailable(
                version = "1.1.0",
                downloadUrl = "https://github.com/app.apk",
                sha256 = null,
                releaseNotesUrl = null,
                releaseNotes = null,
            ),
        )
        val api = UpdateCheckCoordinator(listOf(mirror, github))

        val result = api.check(UpdateCheckRequest(currentVersion = "1.0.0"))

        assertEquals(
            UpdateCheckResult.UpdateAvailable(
                source = UpdateSource.GITHUB,
                version = "1.1.0",
                downloadUrl = "https://github.com/app.apk",
                sha256 = null,
                releaseNotesUrl = null,
                releaseNotes = null,
            ),
            result,
        )
        assertTrue(mirror.invoked)
        assertTrue(github.invoked)
    }

    @Test
    fun `mirror missing apk asset also falls back to github`() = runBlocking {
        val mirror = FakeChecker(
            UpdateSource.MIRROR_CHYAN,
            SourceCheckResult.Failed(UpdateCheckFailure.NO_MATCHING_ASSET),
        )
        val github = FakeChecker(UpdateSource.GITHUB, SourceCheckResult.UpToDate("1.0.0"))
        val api = UpdateCheckCoordinator(listOf(mirror, github))

        val result = api.check(UpdateCheckRequest(currentVersion = "1.0.0"))

        assertEquals(UpdateCheckResult.UpToDate(UpdateSource.GITHUB, "1.0.0"), result)
        assertTrue(github.invoked)
    }

    @Test
    fun `mirror missing configuration falls back to github`() = runBlocking {
        val mirror = FakeChecker(
            UpdateSource.MIRROR_CHYAN,
            SourceCheckResult.Failed(
                UpdateCheckFailure.MISSING_CONFIGURATION,
                "MirrorChyan resource id is missing",
            ),
        )
        val github = FakeChecker(UpdateSource.GITHUB, SourceCheckResult.UpToDate("1.0.0"))
        val api = UpdateCheckCoordinator(listOf(mirror, github))

        val result = api.check(UpdateCheckRequest(currentVersion = "1.0.0"))

        assertEquals(UpdateCheckResult.UpToDate(UpdateSource.GITHUB, "1.0.0"), result)
        assertTrue(github.invoked)
    }

    @Test
    fun `mirror failure reports github alternative without querying github`() = runBlocking {
        val mirror = FakeChecker(
            UpdateSource.MIRROR_CHYAN,
            SourceCheckResult.Failed(UpdateCheckFailure.NETWORK),
        )
        val github = FakeChecker(UpdateSource.GITHUB, SourceCheckResult.UpToDate("1.0.0"))
        val api = UpdateCheckCoordinator(listOf(mirror, github))

        val result = api.check(UpdateCheckRequest(currentVersion = "1.0.0"))

        assertEquals(
            UpdateCheckResult.SourceFailed(
                source = UpdateSource.MIRROR_CHYAN,
                reason = UpdateCheckFailure.NETWORK,
                alternativeSource = UpdateSource.GITHUB,
            ),
            result,
        )
        assertTrue(mirror.invoked)
        assertFalse(github.invoked)
    }

    @Test
    fun `mirror up-to-date cross-checks github and keeps preferred when alt agrees up-to-date`() = runBlocking {
        val mirror = FakeChecker(
            UpdateSource.MIRROR_CHYAN,
            SourceCheckResult.UpToDate("1.0.0"),
        )
        val github = FakeChecker(
            UpdateSource.GITHUB,
            SourceCheckResult.UpToDate("0.9.0"),
        )
        val api = UpdateCheckCoordinator(listOf(mirror, github))

        val result = api.check(UpdateCheckRequest(currentVersion = "1.0.0"))

        assertEquals(
            UpdateCheckResult.UpToDate(UpdateSource.MIRROR_CHYAN, "1.0.0"),
            result,
        )
        assertTrue(mirror.invoked)
        assertTrue(github.invoked)
    }

    @Test
    fun `mirror up-to-date takes github update when alt has a newer version`() = runBlocking {
        val mirror = FakeChecker(
            UpdateSource.MIRROR_CHYAN,
            SourceCheckResult.UpToDate("1.0.0"),
        )
        val github = FakeChecker(
            UpdateSource.GITHUB,
            SourceCheckResult.UpdateAvailable(
                version = "1.1.0",
                downloadUrl = "https://github.com/app.apk",
                sha256 = null,
                releaseNotesUrl = null,
                releaseNotes = null,
            ),
        )
        val api = UpdateCheckCoordinator(listOf(mirror, github))

        val result = api.check(UpdateCheckRequest(currentVersion = "1.0.0"))

        assertEquals(
            UpdateCheckResult.UpdateAvailable(
                source = UpdateSource.GITHUB,
                version = "1.1.0",
                downloadUrl = "https://github.com/app.apk",
                sha256 = null,
                releaseNotesUrl = null,
                releaseNotes = null,
            ),
            result,
        )
        assertTrue(mirror.invoked)
        assertTrue(github.invoked)
    }

    @Test
    fun `mirror up-to-date keeps preferred when alt check fails`() = runBlocking {
        val mirror = FakeChecker(
            UpdateSource.MIRROR_CHYAN,
            SourceCheckResult.UpToDate("1.0.0"),
        )
        val github = FakeChecker(
            UpdateSource.GITHUB,
            SourceCheckResult.Failed(UpdateCheckFailure.NETWORK),
        )
        val api = UpdateCheckCoordinator(listOf(mirror, github))

        val result = api.check(UpdateCheckRequest(currentVersion = "1.0.0"))

        assertEquals(
            UpdateCheckResult.UpToDate(UpdateSource.MIRROR_CHYAN, "1.0.0"),
            result,
        )
        assertTrue(mirror.invoked)
        assertTrue(github.invoked)
    }

    private class FakeChecker(
        override val source: UpdateSource,
        private val result: SourceCheckResult,
    ) : UpdateSourceChecker {
        var invoked = false
            private set

        override suspend fun check(request: UpdateCheckRequest): SourceCheckResult {
            invoked = true
            return result
        }
    }
}
