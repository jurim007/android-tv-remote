package com.jurim007.androidtvremote.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvTypeLabelTest {
    private val androidTv = TvDevice("TCL", "10.0.0.3", serverCertificateFingerprint = "pin")
    private val webOs = TvDevice("Sunny", "10.0.0.4", TvDevice.WEBOS_PORT, platform = TvPlatform.WEBOS)

    @Test
    fun webOsIsAlwaysLabeled() {
        assertEquals("webOS", tvTypeLabel(webOs, TvConnectionState()))
    }

    @Test
    fun androidBasedTvsWaitForDetection() {
        assertNull(tvTypeLabel(androidTv, TvConnectionState()))
        assertNull(tvTypeLabel(null, TvConnectionState()))
    }

    @Test
    fun savedDetectionIsUsed() {
        assertEquals("Google TV", tvTypeLabel(androidTv.copy(isGoogleTv = true), TvConnectionState()))
        assertEquals("Android TV", tvTypeLabel(androidTv.copy(isGoogleTv = false), TvConnectionState()))
    }

    @Test
    fun liveDetectionAppliesOnlyToTheConnectedTv() {
        val connected = TvConnectionState(TvConnectionPhase.CONNECTED, androidTv, detectedGoogleTv = true)
        assertEquals("Google TV", tvTypeLabel(androidTv, connected))
        assertNull(tvTypeLabel(androidTv.copy(host = "10.0.0.9"), connected))
    }
}
