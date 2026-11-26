package com.jurim007.androidtvremote.data.webos

import com.jurim007.androidtvremote.domain.RemoteCommand
import com.jurim007.androidtvremote.domain.TvDevice
import com.jurim007.androidtvremote.domain.TvInstalledApp
import com.jurim007.androidtvremote.domain.TvPlatform
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebOsProtocolTest {
    private val tv = TvDevice("Sunny", "192.168.8.128", TvDevice.WEBOS_PORT, platform = TvPlatform.WEBOS)

    @Test
    fun ssdpRepliesFromWebOsTvsAreRecognized() {
        // Captured from a Sunny webOS Hub TV.
        val reply = "HTTP/1.1 200 OK\r\nLocation: http://192.168.8.128:1906/\r\nCache-Control: max-age=1800\r\n" +
            "Server: WebOS/4.1.0 UPnP/1.0\r\nEXT: \r\n" +
            "USN: uuid:e820299e-fac6-5275-e3e3-28b445658c95::urn:lge-com:service:webos-second-screen:1\r\n" +
            "ST: urn:lge-com:service:webos-second-screen:1\r\n\r\n"
        assertEquals("http://192.168.8.128:1906/", WebOsDiscovery.parseSearchResponse(reply)?.location)
        assertNull(WebOsDiscovery.parseSearchResponse(reply.replace("webos-second-screen", "dial")))
        assertNull(WebOsDiscovery.parseSearchResponse("NOTIFY * HTTP/1.1\r\nST: urn:lge-com:service:webos-second-screen:1\r\n"))
    }

    @Test
    fun remoteButtonsUseTheTvsButtonNames() {
        assertEquals("ENTER", WebOsRemoteController.buttonName(RemoteCommand.Select))
        assertEquals("VOLUMEUP", WebOsRemoteController.buttonName(RemoteCommand.VolumeUp))
        assertEquals("CHANNELDOWN", WebOsRemoteController.buttonName(RemoteCommand.ChannelDown))
        assertNull(WebOsRemoteController.buttonName(RemoteCommand.Power))
        assertNull(WebOsRemoteController.buttonName(RemoteCommand.LaunchApp("netflix")))
    }

    @Test
    fun pointerSocketStaysEncrypted() {
        val secure = "wss://192.168.8.128:3001/resources/abc/netinput.pointer.sock"
        assertEquals(secure, WebOsRemoteController.inputUrl(secure, tv))
        assertEquals(
            secure,
            WebOsRemoteController.inputUrl("ws://192.168.8.128:3000/resources/abc/netinput.pointer.sock", tv),
        )
        assertEquals("wss://192.168.8.128:3001", WebOsRemoteController.url(tv))
    }

    @Test
    fun launchPointsBecomeInstalledApps() {
        val payload = JSONObject(
            """{"returnValue":true,"launchPoints":[
                {"id":"netflix","title":"Netflix","icon":"http://x/icon.png"},
                {"id":"youtube.leanback.v4","title":""},
                {"id":"netflix","title":"Duplicate"},
                {"title":"No id"}
            ]}""",
        )
        assertEquals(
            listOf(TvInstalledApp("netflix", "Netflix"), TvInstalledApp("youtube.leanback.v4", "youtube.leanback.v4")),
            WebOsRemoteController.parseLaunchPoints(payload),
        )
        assertNull(WebOsRemoteController.parseLaunchPoints(JSONObject("{\"returnValue\":true}")))
    }

    @Test
    fun macAddressesAreFoundInConnectionInfo() {
        val payload = JSONObject(
            """{"returnValue":true,
                "wiredInfo":{"macAddress":"a8-23-fe-01-02-03"},
                "wifiInfo":{"macAddress":"A8:23:FE:01:02:04"},
                "p2pInfo":{"macAddress":"00:00:00:00:00:00"},
                "other":[{"macAddress":"not-a-mac"}]}""",
        )
        assertEquals(
            setOf("A8:23:FE:01:02:03", "A8:23:FE:01:02:04"),
            WebOsRemoteController.collectMacAddresses(payload).toSet(),
        )
    }

    @Test
    fun magicPacketRepeatsTheMacSixteenTimes() {
        val packet = WebOsRemoteController.magicPacket("A8:23:FE:01:02:03")!!
        assertEquals(6 + 16 * 6, packet.size)
        assertTrue(packet.take(6).all { it == 0xFF.toByte() })
        assertEquals(0xA8.toByte(), packet[6])
        assertEquals(0x03.toByte(), packet.last())
        assertNull(WebOsRemoteController.magicPacket("bad"))
    }

    @Test
    fun registrationOffersSignedAndUnsignedManifests() {
        val signed = WebOsRegistration.message(clientKey = null, signed = true)
        assertEquals("register", signed.getString("type"))
        val signedManifest = signed.getJSONObject("payload").getJSONObject("manifest")
        assertTrue(signedManifest.has("signatures"))
        assertFalse(signed.getJSONObject("payload").has("client-key"))

        val unsigned = WebOsRegistration.message(clientKey = "key", signed = false)
        val payload = unsigned.getJSONObject("payload")
        assertEquals("key", payload.getString("client-key"))
        val manifest = payload.getJSONObject("manifest")
        assertFalse(manifest.has("signatures"))
        val permissions = List(manifest.getJSONArray("permissions").length()) {
            manifest.getJSONArray("permissions").getString(it)
        }
        assertTrue("READ_INSTALLED_APPS" in permissions && "TEST_SECURE" in permissions)
        assertEquals(permissions.size, permissions.toSet().size)
    }
}
