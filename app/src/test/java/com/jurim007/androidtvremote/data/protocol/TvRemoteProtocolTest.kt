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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TvRemoteProtocolTest {
    @Test
    fun insertsAtTheSelectionWithTheFocusedTvFieldAndTextCounter() {
        val editor = ProtoWriter().int(1, 42).int(2, 1).build()
        val initialStatus = ProtoWriter().int(1, 6).int(3, 2).int(4, 2).build()
        val opened = ProtoWriter().bytes(20, ProtoWriter().bytes(1, editor)
            .bytes(2, initialStatus).build()).build()
        val field = TvRemoteProtocol.updateImeField(opened, null)
        assertEquals(TvImeField(42, 6, 2, 2), field)

        val newStatus = ProtoWriter().int(1, 7).int(3, 4).int(4, 1).build()
        val update = ProtoWriter().bytes(22, ProtoWriter().bytes(2, newStatus).build()).build()
        val updated = TvRemoteProtocol.updateImeField(update, field)
        assertEquals(TvImeField(42, 7, 1, 4), updated)
        assertArrayEquals(
            byteArrayOf(
                0xaa.toByte(), 1, 23, 0x08, 42, 0x10, 7,
                0x1a, 6, 0x0a, 4, 0x10, 1, 0x18, 4,
                0x1a, 9, 0x12, 7, 0x08, 1, 0x10, 4, 0x1a, 1, 'x'.code.toByte(),
            ),
            TvRemoteProtocol.textEdit("x", updated),
        )
    }

    @Test
    fun typingWithoutAFocusedFieldDoesNotDeleteExistingText() {
        assertArrayEquals(
            byteArrayOf(0xaa.toByte(), 1, 7, 0x1a, 5, 0x12, 3, 0x1a, 1, 'A'.code.toByte()),
            TvRemoteProtocol.textEdit("A", null),
        )
    }

    @Test
    fun voiceMessagesCarryTheSessionIdAndPcmPayload() {
        assertArrayEquals(
            byteArrayOf(0x52, 0x04, 0x08, 84, 0x10, 0x03),
            TvRemoteProtocol.requestVoice(),
        )
        val begin = TvRemoteProtocol.beginVoice(123)
        assertEquals(123, TvRemoteProtocol.voiceSessionId(begin, 30))
        val samples = byteArrayOf(1, -1)
        val audio = TvRemoteProtocol.voiceAudio(123, samples)
        assertEquals(123, TvRemoteProtocol.voiceSessionId(audio, 31))
        assertArrayEquals(samples, TvRemoteProtocol.remoteField(
            checkNotNull(TvRemoteProtocol.remoteField(audio, 31)), 2,
        ))
        assertEquals(123, TvRemoteProtocol.voiceSessionId(TvRemoteProtocol.endVoice(123), 32))
    }

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
    fun enterInjectsAndroidEnterKey() {
        val keyCode = checkNotNull(TvRemoteProtocol.keyCode(RemoteCommand.Enter()))
        assertArrayEquals(
            byteArrayOf(0x52, 0x04, 0x08, 66, 0x10, 0x03),
            TvRemoteProtocol.keyCommand(keyCode, TvRemoteProtocol.DIRECTION_SHORT),
        )
    }

    @Test
    fun enterUsesTheFocusedEditorsActionAndKeepsTheDraftInTheSameBatch() {
        val editor = ProtoWriter().int(1, 42).int(3, 0x1c000005).build()
        val opened = ProtoWriter().bytes(20, ProtoWriter().bytes(1, editor).build()).build()
        val field = TvRemoteProtocol.updateImeField(opened, null)
        assertEquals(5, field?.editorAction)

        val message = checkNotNull(TvRemoteProtocol.confirmEditor("A", field))
        val batch = ProtoWire.parse(checkNotNull(TvRemoteProtocol.remoteField(message, 21)))
        assertEquals(42L, batch.first { it.number == 1 }.varint)
        val edits = batch.filter { it.number == 3 }.map { ProtoWire.parse(checkNotNull(it.bytes)).single() }
        assertEquals(listOf(1, 2, 5), edits.map { it.number })
        assertArrayEquals(byteArrayOf(8, 5), edits.last().bytes)

        val withoutDraft = checkNotNull(TvRemoteProtocol.confirmEditor("", field))
        assertEquals(1, ProtoWire.parse(checkNotNull(TvRemoteProtocol.remoteField(withoutDraft, 21)))
            .count { it.number == 3 })
        assertNull(TvRemoteProtocol.confirmEditor("", TvImeField(42)))
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
        val name = "TVControl-unique-client"
        val request = TvRemoteProtocol.pairingRequest(name)
        val fields = ProtoWire.parse(checkNotNull(TvRemoteProtocol.remoteField(request, 10)))
        assertEquals("atvremote", fields.first { it.number == 1 }.bytes?.toString(Charsets.UTF_8))
        assertEquals(name, fields.first { it.number == 2 }.bytes?.toString(Charsets.UTF_8))

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
