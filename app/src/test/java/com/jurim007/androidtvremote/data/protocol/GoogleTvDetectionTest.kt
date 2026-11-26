package com.jurim007.androidtvremote.data.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleTvDetectionTest {
    /** Builds the TV's text-input message, which names the app in the foreground. */
    private fun imeKeyInject(appPackage: String): ByteArray {
        val appInfo = ProtoWriter().int(1, 7).int(3, 1).string(12, appPackage).build()
        val inject = ProtoWriter().bytes(1, appInfo).build()
        return ProtoWriter().bytes(20, inject).build()
    }

    @Test
    fun foregroundAppIsReadFromTextInputMessages() {
        assertEquals(
            "com.google.android.apps.tv.launcherx",
            TvRemoteProtocol.foregroundApp(imeKeyInject("com.google.android.apps.tv.launcherx")),
        )
        assertNull(TvRemoteProtocol.foregroundApp(ProtoWriter().bytes(8, ProtoWriter().int(1, 1).build()).build()))
        assertNull(TvRemoteProtocol.foregroundApp(ProtoWriter().bytes(20, ProtoWriter().build()).build()))
    }

    @Test
    fun homeScreensTellGoogleTvFromAndroidTv() {
        assertEquals(true, TvRemoteProtocol.isGoogleTvLauncher("com.google.android.apps.tv.launcherx"))
        assertEquals(false, TvRemoteProtocol.isGoogleTvLauncher("com.google.android.tvlauncher"))
        assertEquals(false, TvRemoteProtocol.isGoogleTvLauncher("com.google.android.leanbacklauncher"))
        assertNull(TvRemoteProtocol.isGoogleTvLauncher("com.google.android.youtube.tv"))
    }

    @Test
    fun foregroundAppMessagesStillOpenTheTextField() {
        // Reading the app must not change how the same message updates the text field.
        val field = TvRemoteProtocol.updateImeField(imeKeyInject("com.google.android.youtube.tv"), null)
        assertEquals(7, field?.id)
    }
}
