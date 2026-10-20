package com.dashdazz.light

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun parsesTagWithVPrefix() {
        assertEquals(73, UpdateChecker.versionCodeFromTag("v2.0.73"))
    }

    @Test
    fun parsesTagWithoutVPrefix() {
        assertEquals(73, UpdateChecker.versionCodeFromTag("2.0.73"))
    }

    @Test
    fun toleratesSurroundingWhitespace() {
        assertEquals(80, UpdateChecker.versionCodeFromTag("  v2.0.80\n"))
    }

    @Test
    fun handlesThreeSegmentAndTwoSegmentTags() {
        assertEquals(9, UpdateChecker.versionCodeFromTag("v1.9"))
        assertEquals(5, UpdateChecker.versionCodeFromTag("v2.0.5"))
    }

    @Test
    fun rejectsMalformedTags() {
        assertNull(UpdateChecker.versionCodeFromTag(""))
        assertNull(UpdateChecker.versionCodeFromTag("latest"))
        assertNull(UpdateChecker.versionCodeFromTag("v2.0.x"))
        assertNull(UpdateChecker.versionCodeFromTag("2"))
    }

    @Test
    fun versionCodeMatchesTagPatchSoNewerTagIsDetected() {
        val current = 80
        val newer = UpdateChecker.versionCodeFromTag("v2.0.81")
        val older = UpdateChecker.versionCodeFromTag("v2.0.79")
        assertEquals(true, newer != null && newer > current)
        assertEquals(false, older != null && older > current)
    }

    @Test
    fun statusRoundTripsThroughEncodeDecode() {
        val statuses = listOf(
            UpdateChecker.UpdateStatus.Idle,
            UpdateChecker.UpdateStatus.Checking,
            UpdateChecker.UpdateStatus.UpToDate,
            UpdateChecker.UpdateStatus.Available("v2.0.81", 81),
            UpdateChecker.UpdateStatus.Downloading("v2.0.81", 42),
            UpdateChecker.UpdateStatus.NeedsInstallPermission("v2.0.81"),
            UpdateChecker.UpdateStatus.Failed("rate_limited")
        )
        for (s in statuses) {
            assertEquals(s, UpdateChecker.decode(UpdateChecker.encode(s)))
        }
    }

    @Test
    fun decodeFallsBackToIdleForGarbage() {
        assertEquals(UpdateChecker.UpdateStatus.Idle, UpdateChecker.decode("garbage"))
        assertEquals(UpdateChecker.UpdateStatus.Idle, UpdateChecker.decode(""))
    }
}