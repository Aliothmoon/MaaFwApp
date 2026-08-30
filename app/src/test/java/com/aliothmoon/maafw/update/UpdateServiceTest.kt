package com.aliothmoon.maafw.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateServiceTest {

    @Test
    fun `mirror missing package falls back to github`() = runBlocking {
        val mirror = FakeChecker(UpdateSource.MIRRORCHYAN, failed(UpdateCheckFailure.RESOURCE_NOT_FOUND))
        val github = FakeChecker(
            UpdateSource.GITHUB,
            UpdateCheckResult.UpdateAvailable(UpdateSource.GITHUB, UpdateInfo("1.1.0")),
        )
        val service = UpdateService(listOf(mirror, github), emptyList())

        assertEquals(
            UpdateCheckResult.UpdateAvailable(UpdateSource.GITHUB, UpdateInfo("1.1.0")),
            service.checkUpdate(checkRequest()),
        )
        assertTrue(mirror.invoked)
        assertTrue(github.invoked)
    }

    @Test
    fun `mirror missing apk asset also falls back to github`() = runBlocking {
        val mirror = FakeChecker(UpdateSource.MIRRORCHYAN, failed(UpdateCheckFailure.NO_MATCHING_ASSET))
        val github = FakeChecker(UpdateSource.GITHUB, UpdateCheckResult.UpToDate(UpdateSource.GITHUB, "1.0.0"))
        val service = UpdateService(listOf(mirror, github), emptyList())

        assertEquals(
            UpdateCheckResult.UpToDate(UpdateSource.GITHUB, "1.0.0"),
            service.checkUpdate(checkRequest()),
        )
        assertTrue(github.invoked)
    }

    @Test
    fun `mirror missing configuration falls back to github`() = runBlocking {
        val mirror = FakeChecker(UpdateSource.MIRRORCHYAN, failed(UpdateCheckFailure.MISSING_CONFIGURATION))
        val github = FakeChecker(UpdateSource.GITHUB, UpdateCheckResult.UpToDate(UpdateSource.GITHUB, "1.0.0"))
        val service = UpdateService(listOf(mirror, github), emptyList())

        assertEquals(
            UpdateCheckResult.UpToDate(UpdateSource.GITHUB, "1.0.0"),
            service.checkUpdate(checkRequest()),
        )
        assertTrue(github.invoked)
    }

    @Test
    fun `mirror failure reports github alternative without querying github`() = runBlocking {
        val mirror = FakeChecker(UpdateSource.MIRRORCHYAN, failed(UpdateCheckFailure.NETWORK))
        val github = FakeChecker(UpdateSource.GITHUB, UpdateCheckResult.UpToDate(UpdateSource.GITHUB, "1.0.0"))
        val service = UpdateService(listOf(mirror, github), emptyList())

        assertEquals(
            UpdateCheckResult.SourceFailed(
                source = UpdateSource.MIRRORCHYAN,
                reason = UpdateCheckFailure.NETWORK,
                alternativeSource = UpdateSource.GITHUB,
            ),
            service.checkUpdate(checkRequest()),
        )
        assertTrue(mirror.invoked)
        assertFalse(github.invoked)
    }

    @Test
    fun `mirror up-to-date cross-checks github and keeps preferred when alt agrees up-to-date`() = runBlocking {
        val mirror = FakeChecker(UpdateSource.MIRRORCHYAN, UpdateCheckResult.UpToDate(UpdateSource.MIRRORCHYAN, "1.0.0"))
        val github = FakeChecker(UpdateSource.GITHUB, UpdateCheckResult.UpToDate(UpdateSource.GITHUB, "0.9.0"))
        val service = UpdateService(listOf(mirror, github), emptyList())

        assertEquals(
            UpdateCheckResult.UpToDate(UpdateSource.MIRRORCHYAN, "1.0.0"),
            service.checkUpdate(checkRequest()),
        )
        assertTrue(mirror.invoked)
        assertTrue(github.invoked)
    }

    @Test
    fun `mirror up-to-date takes github update when alt has a newer version`() = runBlocking {
        val mirror = FakeChecker(UpdateSource.MIRRORCHYAN, UpdateCheckResult.UpToDate(UpdateSource.MIRRORCHYAN, "1.0.0"))
        val github = FakeChecker(
            UpdateSource.GITHUB,
            UpdateCheckResult.UpdateAvailable(UpdateSource.GITHUB, UpdateInfo("1.1.0")),
        )
        val service = UpdateService(listOf(mirror, github), emptyList())

        assertEquals(
            UpdateCheckResult.UpdateAvailable(UpdateSource.GITHUB, UpdateInfo("1.1.0")),
            service.checkUpdate(checkRequest()),
        )
        assertTrue(mirror.invoked)
        assertTrue(github.invoked)
    }

    @Test
    fun `mirror up-to-date keeps preferred when alt check fails`() = runBlocking {
        val mirror = FakeChecker(UpdateSource.MIRRORCHYAN, UpdateCheckResult.UpToDate(UpdateSource.MIRRORCHYAN, "1.0.0"))
        val github = FakeChecker(UpdateSource.GITHUB, failed(UpdateCheckFailure.NETWORK))
        val service = UpdateService(listOf(mirror, github), emptyList())

        assertEquals(
            UpdateCheckResult.UpToDate(UpdateSource.MIRRORCHYAN, "1.0.0"),
            service.checkUpdate(checkRequest()),
        )
        assertTrue(mirror.invoked)
        assertTrue(github.invoked)
    }

    @Test
    fun `resolve dispatches to the resolver of the requested source`() = runBlocking {
        val resolved = ResolvedUpdate(
            source = UpdateSource.GITHUB,
            version = "v1.1.0",
            downloadUrl = "https://example.com/app.apk",
            sha256 = "a".repeat(64),
        )
        val github = FakeResolver(UpdateSource.GITHUB, UpdateResolveResult.Resolved(resolved))
        val mirror = FakeResolver(
            UpdateSource.MIRRORCHYAN,
            UpdateResolveResult.Failed(UpdateSource.MIRRORCHYAN, UpdateCheckFailure.NETWORK),
        )
        val service = UpdateService(emptyList(), listOf(mirror, github))

        val result = service.resolveDownload(
            resolveRequest(UpdateSource.GITHUB),
        )

        assertEquals(UpdateResolveResult.Resolved(resolved), result)
        assertTrue(github.invoked)
        assertFalse(mirror.invoked)
    }

    @Test
    fun `resolve without registered resolver reports missing configuration`() = runBlocking {
        val service = UpdateService(emptyList(), emptyList())

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

    private class FakeChecker(
        override val source: UpdateSource,
        private val result: UpdateCheckResult,
    ) : UpdateVersionChecker {
        var invoked = false
            private set

        override suspend fun check(request: UpdateCheckRequest): UpdateCheckResult {
            invoked = true
            return result
        }
    }

    private class FakeResolver(
        override val source: UpdateSource,
        private val result: UpdateResolveResult,
    ) : UpdateDownloadUrlResolver {
        var invoked = false
            private set

        override suspend fun resolve(request: UpdateResolveRequest): UpdateResolveResult {
            invoked = true
            return result
        }
    }
}
