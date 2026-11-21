package com.jurim007.androidtvremote.data.protocol

import com.jurim007.androidtvremote.domain.RemoteCommand
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TvRemoteProtocolTest {
    @Test
    fun appLaunchUsesPackageIdWhenNoExplicitLinkIsSet() {
        val message = TvRemoteProtocol.launchApp(RemoteCommand.LaunchApp("com.example.tv"))
        val link = ProtoWire.parse(checkNotNull(TvRemoteProtocol.remoteField(message, 90))).single()
        assertEquals("market://launch?id=com.example.tv", link.bytes?.toString(Charsets.UTF_8))
    }

    @Test
    fun knownAppsUseBuiltInLinks() {
        fun launchLink(packageName: String): String? {
            val message = TvRemoteProtocol.launchApp(RemoteCommand.LaunchApp(packageName))
            return ProtoWire.parse(checkNotNull(TvRemoteProtocol.remoteField(message, 90)))
                .single().bytes?.toString(Charsets.UTF_8)
        }

        val youtube = "com.google.android.youtube.tv"
        assertEquals("https://www.youtube.com", launchLink(youtube))
        assertEquals("cloudstreamapp://open", launchLink("com.lagradost.cloudstream3"))
        assertEquals("netflix://", launchLink("com.netflix.ninja"))
        assertEquals("https://app.primevideo.com", launchLink("com.amazon.amazonvideo.livingroom"))
        assertEquals("https://www.disneyplus.com", launchLink("com.disney.disneyplus"))
    }

    @Test
    fun pongEchoesTheTvSequence() {
        val ping = byteArrayOf(0x42, 0x05, 0x08, 0x96.toByte(), 0x01, 0x10, 0x01)
        assertArrayEquals(
            byteArrayOf(0x4a, 0x03, 0x08, 0x96.toByte(), 0x01),
            TvRemoteProtocol.pingResponse(ping),
        )
        assertArrayEquals(
            byteArrayOf(0x4a, 0x02, 0x08, 0x00),
            TvRemoteProtocol.pingResponse(byteArrayOf(0x42, 0x00)),
        )
    }

    @Test
    fun deleteInjectsAndroidBackspace() {
        val keyCode = checkNotNull(TvRemoteProtocol.keyCode(RemoteCommand.Delete))
        assertArrayEquals(
            byteArrayOf(0x52, 0x04, 0x08, 67, 0x10, 0x03),
            TvRemoteProtocol.keyCommand(keyCode, TvRemoteProtocol.DIRECTION_SHORT),
        )
    }

    @Test
    fun protobufFieldsAndLengthDelimitedFramesRoundTrip() {
        val message = ProtoWriter()
            .int(1, 150)
            .string(2, "TVControl")
            .bytes(3, byteArrayOf(0, 1, 2, 0xff.toByte()))
            .build()
        val output = ByteArrayOutputStream()

        ProtoWire.writeDelimited(output, message)

        val decoded = ProtoWire.readDelimited(ByteArrayInputStream(output.toByteArray()))
        val fields = ProtoWire.parse(checkNotNull(decoded))
        assertEquals(150L, fields[0].varint)
        assertEquals("TVControl", fields[1].bytes?.toString(Charsets.UTF_8))
        assertArrayEquals(byteArrayOf(0, 1, 2, 0xff.toByte()), fields[2].bytes)
        assertEquals(null, ProtoWire.readDelimited(ByteArrayInputStream(byteArrayOf())))
    }

    @Test
    fun pairingRequestUsesExpectedOuterFieldAndStatus() {
        val request = TvRemoteProtocol.pairingRequest()

        assertEquals(200L, TvRemoteProtocol.pairingStatus(request))
        assertEquals(true, TvRemoteProtocol.hasPairingPayload(request, 10))
    }

    @Test
    fun pairingNegotiatesSixDigitHexadecimalInput() {
        val optionsOuter = TvRemoteProtocol.pairingOptions()
        val optionsPayload = checkNotNull(TvRemoteProtocol.remoteField(optionsOuter, 20))
        val options = ProtoWire.parse(optionsPayload)
        val inputEncoding = ProtoWire.parse(checkNotNull(options.first { it.number == 1 }.bytes))

        assertEquals(3L, inputEncoding.first { it.number == 1 }.varint)
        assertEquals(6L, inputEncoding.first { it.number == 2 }.varint)
        assertEquals(1L, options.first { it.number == 3 }.varint)
    }

    @Test
    fun pairingPinMustBeHexAndMatchTheChecksum() {
        val clientKey = generateRsaPublicKey()
        val serverKey = generateRsaPublicKey()
        val nonce = byteArrayOf(0x12, 0x34)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(unsigned(clientKey.modulus))
        digest.update(unsigned(clientKey.publicExponent))
        digest.update(unsigned(serverKey.modulus))
        digest.update(unsigned(serverKey.publicExponent))
        digest.update(nonce)
        val expectedSecret = digest.digest()
        val pin = "%02X1234".format(expectedSecret[0].toInt() and 0xff)

        assertArrayEquals(expectedSecret, TvRemoteProtocol.pairingSecret(pin, clientKey, serverKey))
        assertThrows(IllegalArgumentException::class.java) {
            TvRemoteProtocol.pairingSecret("nothex", clientKey, serverKey)
        }
        val wrongChecksum =
            "%02X1234".format((expectedSecret[0].toInt() + 1) and 0xff)
        assertThrows(IllegalArgumentException::class.java) {
            TvRemoteProtocol.pairingSecret(wrongChecksum, clientKey, serverKey)
        }
    }

    @Test
    fun rejectsTruncatedLengthDelimitedField() {
        assertThrows(EOFException::class.java) {
            ProtoWire.readDelimited(ByteArrayInputStream(byteArrayOf(3, 10)))
        }
    }

    private fun generateRsaPublicKey(): RSAPublicKey =
        KeyPairGenerator.getInstance("RSA")
            .apply { initialize(1024) }
            .generateKeyPair()
            .public as RSAPublicKey

    private fun unsigned(number: java.math.BigInteger): ByteArray =
        number.toByteArray().let { bytes ->
            if (bytes.size > 1 && bytes[0] == 0.toByte()) {
                bytes.copyOfRange(1, bytes.size)
            } else {
                bytes
            }
        }
}
