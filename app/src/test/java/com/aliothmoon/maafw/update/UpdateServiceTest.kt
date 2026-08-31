package com.aliothmoon.maafw.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateServiceTest {

    @Test
    fun `check dispatches to the selected source only`() = runBlocking {
        val mirror = FakeClient(
            UpdateSource.MIRRORCHYAN,
            checkResult = UpdateCheckResult.UpdateAvailable(UpdateSource.MIRRORCHYAN, UpdateInfo("1.1.0")),
        )
        val github = FakeClient(
            UpdateSource.GITHUB,
            checkResult = UpdateCheckResult.UpdateAvailable(UpdateSource.GITHUB, UpdateInfo("9.9.9")),
        )
        val service = UpdateService(listOf(mirror, github))

        assertEquals(
            UpdateCheckResult.UpdateAvailable(UpdateSource.MIRRORCHYAN, UpdateInfo("1.1.0")),
            service.check(checkRequest(UpdateSource.MIRRORCHYAN)),
        )
        assertTrue(mirror.checkInvoked)
        assertFalse(github.checkInvoked)
    }

    @Test
    fun `check failure is returned as-is without querying the other source`() = runBlocking {
        val mirror = FakeClient(
            UpdateSource.MIRRORCHYAN,
            checkResult = UpdateCheckResult.SourceFailed(UpdateSource.MIRRORCHYAN, UpdateCheckFailure.RESOURCE_NOT_FOUND),
        )
        val github = FakeClient(
            UpdateSource.GITHUB,
            checkResult = UpdateCheckResult.UpdateAvailable(UpdateSource.GITHUB, UpdateInfo("1.1.0")),
        )
        val service = UpdateService(listOf(mirror, github))

        assertEquals(
            UpdateCheckResult.SourceFailed(UpdateSource.MIRRORCHYAN, UpdateCheckFailure.RESOURCE_NOT_FOUND),
            service.check(checkRequest(UpdateSource.MIRRORCHYAN)),
        )
        assertFalse(github.checkInvoked)
    }

    @Test
    fun `up to date is trusted without cross-checking the other source`() = runBlocking {
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
            UpdateCheckResult.UpToDate(UpdateSource.MIRRORCHYAN, "1.0.0"),
            service.check(checkRequest(UpdateSource.MIRRORCHYAN)),
        )
        assertFalse(github.checkInvoked)
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

        val result = service.resolve(resolveRequest(UpdateSource.GITHUB))

        assertEquals(UpdateResolveResult.Resolved(resolved), result)
        assertTrue(github.resolveInvoked)
        assertFalse(mirror.resolveInvoked)
    }

    private fun checkRequest(source: UpdateSource) = UpdateCheckRequest(source = source, currentVersion = "1.0.0", abi = AndroidAbi.ARM64)

    private fun resolveRequest(source: UpdateSource) =
        UpdateResolveRequest(source = source, currentVersion = "1.0.0", abi = AndroidAbi.ARM64)

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
