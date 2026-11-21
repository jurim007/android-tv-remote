package com.jurim007.androidtvremote.data.protocol

import com.jurim007.androidtvremote.domain.RemoteCommand
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

internal object TvRemoteProtocol {
    const val FEATURE_PING = 1
    const val FEATURE_KEY = 1 shl 1
    const val FEATURE_IME = 1 shl 2
    const val FEATURE_VOICE = 1 shl 3
    const val VOICE_FEATURES = FEATURE_KEY or FEATURE_VOICE
    const val FEATURE_POWER = 1 shl 5
    const val FEATURE_VOLUME = 1 shl 6
    const val FEATURE_APP_LINK = 1 shl 9

    const val DIRECTION_START_LONG = 1L
    const val DIRECTION_END_LONG = 2L
    const val DIRECTION_SHORT = 3L

    fun pairingRequest(): ByteArray {
        val request = ProtoWriter()
            .string(1, "atvremote")
            .string(2, "TVControl")
            .build()
        return pairingOuter(10, request)
    }

    fun pairingOptions(): ByteArray {
        val encoding = ProtoWriter().int(1, 3).int(2, 6).build()
        val options = ProtoWriter().bytes(1, encoding).int(3, 1).build()
        return pairingOuter(20, options)
    }

    fun pairingConfiguration(): ByteArray {
        val encoding = ProtoWriter().int(1, 3).int(2, 6).build()
        val configuration = ProtoWriter().bytes(1, encoding).int(2, 1).build()
        return pairingOuter(30, configuration)
    }

    fun pairingSecret(secret: ByteArray): ByteArray {
        val payload = ProtoWriter().bytes(1, secret).build()
        return pairingOuter(40, payload)
    }

    fun hasPairingPayload(message: ByteArray, fieldNumber: Int): Boolean =
        ProtoWire.parse(message).any { it.number == fieldNumber && it.wireType == 2 }

    fun pairingStatus(message: ByteArray): Long? =
        ProtoWire.parse(message).firstOrNull { it.number == 2 }?.varint

    fun remoteFeatures(message: ByteArray): Int? {
        val configure = ProtoWire.parse(message).firstOrNull { it.number == 1 }?.bytes
            ?: return null
        return ProtoWire.parse(configure)
            .firstOrNull { it.number == 1 }
            ?.varint
            ?.toInt()
    }

    fun remoteConfigure(features: Int): ByteArray {
        val deviceInfo = ProtoWriter()
            .int(3, 1)
            .string(4, "1")
            .string(5, "atvremote")
            .string(6, "1.0.0")
            .build()
        val configure = ProtoWriter()
            .int(1, features.toLong())
            .bytes(2, deviceInfo)
            .build()
        return ProtoWriter().bytes(1, configure).build()
    }

    fun remoteSetActive(features: Int): ByteArray {
        val setActive = ProtoWriter().int(1, features.toLong()).build()
        return ProtoWriter().bytes(2, setActive).build()
    }

    fun remoteField(message: ByteArray, fieldNumber: Int): ByteArray? =
        ProtoWire.parse(message)
            .firstOrNull { it.number == fieldNumber && it.wireType == 2 }
            ?.bytes

    fun hasRemoteError(message: ByteArray): Boolean =
        ProtoWire.parse(message).any { it.number == 3 && it.wireType == 2 }

    fun rejectedRemoteCommand(message: ByteArray): ByteArray? =
        remoteField(message, 3)?.let { remoteField(it, 2) }

    fun pingResponse(message: ByteArray): ByteArray? {
        val ping = remoteField(message, 8) ?: return null
        val sequence = ProtoWire.parse(ping).firstOrNull { it.number == 1 }?.varint ?: 0L
        val payload = ProtoWriter().varint(1, sequence).build()
        return ProtoWriter().bytes(9, payload).build()
    }

    fun voiceSessionId(message: ByteArray, field: Int): Int? =
        remoteField(message, field)?.let { payload ->
            (ProtoWire.parse(payload).firstOrNull { it.number == 1 }?.varint ?: 0).toInt()
        }

    fun requestVoice(): ByteArray = keyCommand(84, DIRECTION_SHORT)

    fun beginVoice(sessionId: Int): ByteArray =
        ProtoWriter().bytes(30, ProtoWriter().int(1, sessionId.toLong()).build()).build()

    fun voiceAudio(sessionId: Int, samples: ByteArray): ByteArray {
        val payload = ProtoWriter().int(1, sessionId.toLong()).bytes(2, samples).build()
        return ProtoWriter().bytes(31, payload).build()
    }

    fun endVoice(sessionId: Int): ByteArray =
        ProtoWriter().bytes(32, ProtoWriter().int(1, sessionId.toLong()).build()).build()

    fun keyCommand(keyCode: Int, direction: Long): ByteArray {
        val keyInject = ProtoWriter()
            .int(1, keyCode.toLong())
            .int(2, direction)
            .build()
        return ProtoWriter().bytes(10, keyInject).build()
    }

    fun keyCode(command: RemoteCommand): Int? =
        when (command) {
            RemoteCommand.Power -> 26
            RemoteCommand.Back -> 4
            RemoteCommand.Home -> 3
            RemoteCommand.Select -> 23
            RemoteCommand.Up -> 19
            RemoteCommand.Down -> 20
            RemoteCommand.Left -> 21
            RemoteCommand.Right -> 22
            RemoteCommand.VolumeUp -> 24
            RemoteCommand.VolumeDown -> 25
            RemoteCommand.ChannelUp -> 166
            RemoteCommand.ChannelDown -> 167
            RemoteCommand.Mute -> 164
            RemoteCommand.Source -> 178
            RemoteCommand.Delete -> 67
            is RemoteCommand.SendText,
            is RemoteCommand.LaunchApp,
            -> null
        }

    fun launchApp(command: RemoteCommand.LaunchApp): ByteArray {
        val packageName = command.packageName.trim()
        val uri = when (packageName) {
            "com.google.android.youtube.tv" -> "https://www.youtube.com"
            "com.lagradost.cloudstream3",
            "com.lagradost.cloudstream3.prerelease",
            -> "cloudstreamapp://open"
            "com.netflix.ninja" -> "netflix://"
            "com.amazon.amazonvideo.livingroom" -> "https://app.primevideo.com"
            "com.disney.disneyplus" -> "https://www.disneyplus.com"
            else -> "market://launch?id=$packageName"
        }
        val request = ProtoWriter().string(1, uri).build()
        return ProtoWriter().bytes(90, request).build()
    }

    fun textEdit(text: String, field: TvImeField?): ByteArray {
        val batch = ProtoWriter()
        val start = field?.selectionStart ?: 0
        val end = field?.selectionEnd ?: 0
        if (field != null) {
            val select = ProtoWriter().int(2, start.toLong()).int(3, end.toLong()).build()
            batch.int(1, field.id.toLong())
                .int(2, field.counter.toLong())
                .bytes(3, ProtoWriter().bytes(1, select).build())
        }
        val replacement = ProtoWriter()
            .int(1, start.toLong())
            .int(2, end.toLong())
            .string(3, text)
            .build()
        batch.bytes(3, ProtoWriter().bytes(2, replacement).build())
        return ProtoWriter().bytes(21, batch.build()).build()
    }

    fun updateImeField(message: ByteArray, current: TvImeField?): TvImeField? {
        val fields = ProtoWire.parse(message)
        val opened = fields.firstOrNull { it.number == 20 }?.bytes
        if (opened != null) {
            val editor = remoteField(opened, 1) ?: return null
            val id = ProtoWire.parse(editor).intField(1)
            if (id == 0) return null
            val initial = TvImeField(id)
            return remoteField(opened, 2)?.let(initial::withTextStatus) ?: initial
        }
        if (current == null || fields.any { it.number == 24 && it.bytes != null }) return null

        val textUpdate = fields.firstOrNull { it.number == 22 }?.bytes
        val status = textUpdate?.let { remoteField(it, 2) }
        if (status != null) return current.withTextStatus(status)

        val selection = fields.firstOrNull { it.number == 23 }?.bytes
        if (selection != null) {
            val range = ProtoWire.parse(selection)
            return current.withSelection(range.intField(2), range.intField(3))
        }
        return current
    }

    fun pairingSecret(
        pin: String,
        clientKey: RSAPublicKey,
        serverKey: RSAPublicKey,
    ): ByteArray {
        require(pin.matches(PIN_PATTERN)) { "Enter the 6-character hexadecimal code shown on the TV." }
        val pinBytes = pin.chunked(2).map { it.toInt(16).toByte() }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(unsignedBytes(clientKey.modulus))
        digest.update(unsignedBytes(clientKey.publicExponent))
        digest.update(unsignedBytes(serverKey.modulus))
        digest.update(unsignedBytes(serverKey.publicExponent))
        digest.update(pinBytes.drop(1).toByteArray())
        val secret = digest.digest()
        require(secret.first() == pinBytes.first()) { "That code is not valid. Check it and try again." }
        return secret
    }

    private fun pairingOuter(payloadField: Int, payload: ByteArray): ByteArray =
        ProtoWriter()
            .int(1, 2)
            .int(2, 200)
            .bytes(payloadField, payload)
            .build()

    private fun unsignedBytes(number: java.math.BigInteger): ByteArray {
        val encoded = number.toByteArray()
        return if (encoded.size > 1 && encoded[0] == 0.toByte()) {
            encoded.copyOfRange(1, encoded.size)
        } else {
            encoded
        }
    }

    private val PIN_PATTERN = Regex("[0-9A-Fa-f]{6}")
}


internal data class TvImeField(
    val id: Int,
    val counter: Int = 0,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
) {
    fun withTextStatus(status: ByteArray): TvImeField {
        val fields = ProtoWire.parse(status)
        return withSelection(fields.intField(3), fields.intField(4)).copy(counter = fields.intField(1))
    }

    fun withSelection(start: Int, end: Int): TvImeField = copy(
        selectionStart = minOf(start, end).coerceAtLeast(0),
        selectionEnd = maxOf(start, end).coerceAtLeast(0),
    )
}

private fun List<ProtoField>.intField(number: Int): Int =
    firstOrNull { it.number == number }?.varint?.toInt() ?: 0
