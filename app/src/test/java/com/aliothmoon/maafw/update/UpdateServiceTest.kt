package com.aliothmoon.maafw.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateServiceTest {

    @Test
    fun `mirror missing package falls back to github`() = runBlocking {
        val mirror = FakeClient(UpdateSource.MIRRORCHYAN, checkResult = failed(UpdateCheckFailure.RESOURCE_NOT_FOUND))
        val github = FakeClient(
            UpdateSource.GITHUB,
            checkResult = UpdateCheckResult.UpdateAvailable(UpdateSource.GITHUB, UpdateInfo("1.1.0")),
        )
        val service = UpdateService(listOf(mirror, github))

        assertEquals(
            UpdateCheckResult.UpdateAvailable(UpdateSource.GITHUB, UpdateInfo("1.1.0")),
            service.checkUpdate(checkRequest()),
        )
        assertTrue(mirror.checkInvoked)
        assertTrue(github.checkInvoked)
    }

    @Test
    fun `mirror missing apk asset also falls back to github`() = runBlocking {
        val mirror = FakeClient(UpdateSource.MIRRORCHYAN, checkResult = failed(UpdateCheckFailure.NO_MATCHING_ASSET))
        val github = FakeClient(
            UpdateSource.GITHUB,
            checkResult = UpdateCheckResult.UpToDate(UpdateSource.GITHUB, "1.0.0"),
        )
        val service = UpdateService(listOf(mirror, github))

        assertEquals(
            UpdateCheckResult.UpToDate(UpdateSource.GITHUB, "1.0.0"),
            service.checkUpdate(checkRequest()),
        )
        assertTrue(github.checkInvoked)
    }

    @Test
    fun `mirror missing configuration falls back to github`() = runBlocking {
        val mirror = FakeClient(UpdateSource.MIRRORCHYAN, checkResult = failed(UpdateCheckFailure.MISSING_CONFIGURATION))
        val github = FakeClient(
            UpdateSource.GITHUB,
            checkResult = UpdateCheckResult.UpToDate(UpdateSource.GITHUB, "1.0.0"),
        )
        val service = UpdateService(listOf(mirror, github))

        assertEquals(
            UpdateCheckResult.UpToDate(UpdateSource.GITHUB, "1.0.0"),
            service.checkUpdate(checkRequest()),
        )
        assertTrue(github.checkInvoked)
    }

    @Test
    fun `mirror non-fallback failure does not query github`() = runBlocking {
        val mirror = FakeClient(UpdateSource.MIRRORCHYAN, checkResult = failed(UpdateCheckFailure.NETWORK))
        val github = FakeClient(
            UpdateSource.GITHUB,
            checkResult = UpdateCheckResult.UpToDate(UpdateSource.GITHUB, "1.0.0"),
        )
        val service = UpdateService(listOf(mirror, github))

        assertEquals(
            UpdateCheckResult.SourceFailed(UpdateSource.MIRRORCHYAN, UpdateCheckFailure.NETWORK),
            service.checkUpdate(checkRequest()),
        )
        assertTrue(mirror.checkInvoked)
        assertFalse(github.checkInvoked)
    }

    @Test
    fun `mirror up-to-date cross-checks github and keeps preferred when alt agrees up-to-date`() = runBlocking {
        val mirror = FakeClient(
            UpdateSource.MIRRORCHYAN,
            checkResult = UpdateCheckResult.UpToDate(UpdateSource.MIRRORCHYAN, "1.0.0"),
        )
        val github = FakeClient(
            UpdateSource.GITHUB,
            checkResult = UpdateCheckResult.UpToDate(UpdateSource.GITHUB, "0.9.0"),
        )
        val service = UpdateService(listOf(mirror, github))

        assertEquals(
            UpdateCheckResult.UpToDate(UpdateSource.MIRRORCHYAN, "1.0.0"),
            service.checkUpdate(checkRequest()),
        )
        assertTrue(mirror.checkInvoked)
        assertTrue(github.checkInvoked)
    }

    @Test
    fun `mirror up-to-date takes github update when alt has a newer version`() = runBlocking {
        val mirror = FakeClient(
            UpdateSource.MIRRORCHYAN,
            checkResult = UpdateCheckResult.UpToDate(UpdateSource.MIRRORCHYAN, "1.0.0"),
        )
        val github = FakeClient(
            UpdateSource.GITHUB,
            checkResult = UpdateCheckResult.UpdateAvailable(UpdateSource.GITHUB, UpdateInfo("1.1.0")),
        )
        val service = UpdateService(listOf(mirror, github))

        assertEquals(
            UpdateCheckResult.UpdateAvailable(UpdateSource.GITHUB, UpdateInfo("1.1.0")),
            service.checkUpdate(checkRequest()),
        )
        assertTrue(mirror.checkInvoked)
        assertTrue(github.checkInvoked)
    }

    @Test
    fun `mirror up-to-date keeps preferred when alt check fails`() = runBlocking {
        val mirror = FakeClient(
            UpdateSource.MIRRORCHYAN,
            checkResult = UpdateCheckResult.UpToDate(UpdateSource.MIRRORCHYAN, "1.0.0"),
        )
        val github = FakeClient(
            UpdateSource.GITHUB,
            checkResult = UpdateCheckResult.SourceFailed(UpdateSource.GITHUB, UpdateCheckFailure.NETWORK),
        )
        val service = UpdateService(listOf(mirror, github))

        assertEquals(
            UpdateCheckResult.UpToDate(UpdateSource.MIRRORCHYAN, "1.0.0"),
            service.checkUpdate(checkRequest()),
        )
        assertTrue(mirror.checkInvoked)
        assertTrue(github.checkInvoked)
    }

    @Test
    fun `resolve dispatches to the client of the requested source`() = runBlocking {
        val resolved = ResolvedUpdate(
            source = UpdateSource.GITHUB,
            version = "v1.1.0",
            downloadUrl = "https://example.com/app.apk",
            sha256 = "a".repeat(64),
        )
        val github = FakeClient(UpdateSource.GITHUB, resolveResult = UpdateResolveResult.Resolved(resolved))
        val mirror = FakeClient(
            UpdateSource.MIRRORCHYAN,
            resolveResult = UpdateResolveResult.Failed(UpdateSource.MIRRORCHYAN, UpdateCheckFailure.NETWORK),
        )
        val service = UpdateService(listOf(mirror, github))

        val result = service.resolveDownload(
            resolveRequest(UpdateSource.GITHUB),
        )

        assertEquals(UpdateResolveResult.Resolved(resolved), result)
        assertTrue(github.resolveInvoked)
        assertFalse(mirror.resolveInvoked)
    }

    @Test
    fun `resolve without registered client reports missing configuration`() = runBlocking {
        val service = UpdateService(emptyList())

        assertEquals(
            UpdateResolveResult.Failed(UpdateSource.GITHUB, UpdateCheckFailure.MISSING_CONFIGURATION),
            service.resolveDownload(resolveRequest(UpdateSource.GITHUB)),
        )
    }

    private fun checkRequest() = UpdateCheckRequest(currentVersion = "1.0.0", abi = AndroidAbi.ARM64)

    private fun resolveRequest(source: UpdateSource) =
        UpdateResolveRequest(source = source, currentVersion = "1.0.0", abi = AndroidAbi.ARM64)

    private fun failed(reason: UpdateCheckFailure) =
        UpdateCheckResult.SourceFailed(UpdateSource.MIRRORCHYAN, reason)

    private class FakeClient(
        override val source: UpdateSource,
        private val checkResult: UpdateCheckResult? = null,
        private val resolveResult: UpdateResolveResult? = null,
    ) : UpdateSourceClient {
        var checkInvoked = false
            private set
        var resolveInvoked = false
            private set

        override suspend fun check(request: UpdateCheckRequest): UpdateCheckResult {
            checkInvoked = true
            return checkNotNull(checkResult) { "check was not expected for $source" }
        }

        override suspend fun resolve(request: UpdateResolveRequest): UpdateResolveResult {
            resolveInvoked = true
            return checkNotNull(resolveResult) { "resolve was not expected for $source" }
        }
    }
}
