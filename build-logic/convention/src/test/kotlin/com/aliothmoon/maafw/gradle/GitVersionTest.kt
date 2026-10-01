package com.aliothmoon.maafw.gradle

import io.github.z4kn4fein.semver.toVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitVersionTest {

    @Test
    fun `a tag on HEAD is used as written`() {
        assertEquals("2.31.0", versionNameFromDescribe("v2.31.0"))
        assertEquals("2.31.0-beta.5", versionNameFromDescribe("v2.31.0-beta.5"))
        assertEquals("2.31.0-rc.1", versionNameFromDescribe("v2.31.0-rc.1"))
        assertEquals("2.31.0", versionNameFromDescribe("2.31.0"))
    }

    @Test
    fun `past a release tag patch is bumped into an alpha`() {
        assertEquals("2.31.1-alpha.3", versionNameFromDescribe("v2.31.0-3-g7a04228"))
    }

    @Test
    fun `past a prerelease tag the prerelease is kept and the distance appended`() {
        assertEquals("2.31.0-beta.5.2", versionNameFromDescribe("v2.31.0-beta.5-2-gc654983"))
        assertEquals("2.31.0-rc.1.1", versionNameFromDescribe("v2.31.0-rc.1-1-gcdf18d1"))
    }

    @Test
    fun `a build past a prerelease tag sorts between that tag and the next one`() {
        val ordered = listOf(
            versionNameFromDescribe("v2.31.0-beta.5"),
            versionNameFromDescribe("v2.31.0-beta.5-2-gc654983"),
            versionNameFromDescribe("v2.31.0-rc.1"),
            versionNameFromDescribe("v2.31.0-rc.1-12-gcdf18d1"),
            versionNameFromDescribe("v2.31.0-rc.2"),
            versionNameFromDescribe("v2.31.0"),
            versionNameFromDescribe("v2.31.0-1-g7a04228"),
        ).map { it.toVersion() }

        ordered.zipWithNext().forEach { (lower, higher) ->
            assertTrue("$lower < $higher", lower < higher)
        }
    }

    @Test
    fun `an unrecognised describe output degrades to itself`() {
        assertEquals("a3ee5cf8", versionNameFromDescribe("a3ee5cf8"))
        assertEquals("2.31.0-rc-1", versionNameFromDescribe("v2.31.0-rc-1"))
        assertEquals("0.0.0-dev", versionNameFromDescribe(""))
    }
}
