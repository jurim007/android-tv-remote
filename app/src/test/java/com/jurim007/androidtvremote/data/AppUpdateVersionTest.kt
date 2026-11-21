package com.jurim007.androidtvremote.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateVersionTest {
    @Test
    fun newerStableVersionsAreComparedNumerically() {
        assertTrue(isNewerRelease("v1.0.1", "1.0.0"))
        assertTrue(isNewerRelease("1.10.0", "1.9.9"))
        assertTrue(isNewerRelease("2.0.0", "1.99.99"))
    }

    @Test
    fun equalOlderPrereleaseAndMalformedVersionsAreIgnored() {
        assertFalse(isNewerRelease("v1.0.0", "1.0.0"))
        assertFalse(isNewerRelease("1.9.99", "1.10.0"))
        assertFalse(isNewerRelease("2.0.0-beta.1", "1.0.0"))
        assertFalse(isNewerRelease("2.0", "1.0.0"))
        assertFalse(isNewerRelease("2.00.0", "1.0.0"))
    }
}
