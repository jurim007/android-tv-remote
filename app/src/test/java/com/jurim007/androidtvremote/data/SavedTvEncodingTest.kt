package com.jurim007.androidtvremote.data

import com.jurim007.androidtvremote.domain.TvDevice
import com.jurim007.androidtvremote.domain.TvPlatform
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedTvEncodingTest {
    @Test
    fun webOsTvsKeepTheirPairingDetails() {
        val tv = TvDevice(
            name = "Sunny",
            host = "192.168.8.128",
            port = TvDevice.WEBOS_PORT,
            serverCertificateFingerprint = "AA:BB",
            platform = TvPlatform.WEBOS,
            clientKey = "client-key",
            macAddresses = listOf("A8:23:FE:01:02:03"),
        )
        val android = TvDevice(
            "Living Room", "10.0.0.2", serverCertificateFingerprint = "CC:DD",
            usesUniqueClientIdentity = true, isGoogleTv = true,
        )
        val encoded = AppShortcutPreferences.encodeTvDevices(listOf(tv, android))
        assertEquals(listOf(tv, android), AppShortcutPreferences.decodeTvDevices(encoded))
    }

    @Test
    fun tvsSavedBeforeWebOsSupportLoadAsAndroidTv() {
        val legacy = """[{"name":"TCL","host":"10.0.0.3","port":6466,"certificateFingerprint":"EE:FF","usesUniqueClientIdentity":true}]"""
        assertEquals(
            listOf(TvDevice("TCL", "10.0.0.3", serverCertificateFingerprint = "EE:FF", usesUniqueClientIdentity = true)),
            AppShortcutPreferences.decodeTvDevices(legacy),
        )
    }
}
