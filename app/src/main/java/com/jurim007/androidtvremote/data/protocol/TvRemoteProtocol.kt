package com.jurim007.androidtvremote.data.protocol

import com.jurim007.androidtvremote.domain.RemoteCommand
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

internal object TvRemoteProtocol {
    const val FEATURE_PING = 1
    const val FEATURE_KEY = 1 shl 1
    const val FEATURE_IME = 1 shl 2
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

    fun pingResponse(message: ByteArray): ByteArray? {
        val ping = remoteField(message, 8) ?: return null
        val sequence = ProtoWire.parse(ping).firstOrNull { it.number == 1 }?.varint ?: 0L
        return ProtoWriter()
            .bytes(9, ProtoWriter().int(1, sequence).build())
            .build()
    }

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
            is RemoteCommand.SendText,
            is RemoteCommand.LaunchApp,
            -> null
        }

    fun appLink(uri: String): ByteArray {
        val request = ProtoWriter().string(1, uri).build()
        return ProtoWriter().bytes(90, request).build()
    }

    fun textEdit(text: String, imeCounter: Int, fieldCounter: Int): ByteArray {
        val imeObject = ProtoWriter()
            .int(1, text.length.toLong())
            .int(2, text.length.toLong())
            .string(3, text)
            .build()
        val editInfo = ProtoWriter()
            .int(1, 1)
            .bytes(2, imeObject)
            .build()
        val batchEdit = ProtoWriter()
            .int(1, imeCounter.toLong())
            .int(2, fieldCounter.toLong())
            .bytes(3, editInfo)
            .build()
        return ProtoWriter().bytes(21, batchEdit).build()
    }

    fun imeCounters(message: ByteArray): Pair<Int?, Int?> {
        val fields = ProtoWire.parse(message)
        val keyInject = fields.firstOrNull { it.number == 20 }?.bytes
        if (keyInject != null) {
            val injectFields = ProtoWire.parse(keyInject)
            val appInfo = injectFields.firstOrNull { it.number == 1 }?.bytes
            val status = injectFields.firstOrNull { it.number == 2 }?.bytes
            return appInfo?.let { ProtoWire.parse(it).firstOrNull { field -> field.number == 1 }?.varint?.toInt() } to
                status?.let { ProtoWire.parse(it).firstOrNull { field -> field.number == 1 }?.varint?.toInt() }
        }

        val showRequest = fields.firstOrNull { it.number == 22 }?.bytes
        val textFieldStatus = showRequest?.let {
            ProtoWire.parse(it).firstOrNull { field -> field.number == 2 }?.bytes
        }
        if (textFieldStatus != null) {
            val counter = ProtoWire.parse(textFieldStatus)
                .firstOrNull { it.number == 1 }
                ?.varint
                ?.toInt()
            return null to counter
        }

        val batchEdit = fields.firstOrNull { it.number == 21 }?.bytes
        if (batchEdit != null) {
            val batchFields = ProtoWire.parse(batchEdit)
            return batchFields.firstOrNull { it.number == 1 }?.varint?.toInt() to
                batchFields.firstOrNull { it.number == 2 }?.varint?.toInt()
        }
        return null to null
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
