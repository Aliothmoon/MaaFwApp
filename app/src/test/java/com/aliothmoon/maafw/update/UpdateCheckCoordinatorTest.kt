package com.aliothmoon.maafw.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckCoordinatorTest {

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
    fun `success is returned directly`() = runBlocking {
        val mirror = FakeChecker(
            UpdateSource.MIRROR_CHYAN,
            SourceCheckResult.UpToDate("1.0.0"),
        )
        val github = FakeChecker(UpdateSource.GITHUB, SourceCheckResult.UpToDate("0.9.0"))
        val api = UpdateCheckCoordinator(listOf(mirror, github))

        val result = api.check(UpdateCheckRequest(currentVersion = "1.0.0"))

        assertEquals(UpdateCheckResult.UpToDate(UpdateSource.MIRROR_CHYAN, "1.0.0"), result)
        assertFalse(github.invoked)
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
