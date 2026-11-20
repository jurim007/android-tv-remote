package com.jurim007.androidtvremote.data.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal data class ProtoField(
    val number: Int,
    val wireType: Int,
    val varint: Long? = null,
    val bytes: ByteArray? = null,
)

internal class ProtoWriter {
    private val output = ByteArrayOutputStream()

    fun int(number: Int, value: Long): ProtoWriter = apply {
        require(number > 0)
        if (value != 0L) {
            writeVarint((number shl 3).toLong())
            writeVarint(value)
        }
    }

    fun bytes(number: Int, value: ByteArray): ProtoWriter = apply {
        require(number > 0)
        writeVarint(((number shl 3) or LENGTH_DELIMITED).toLong())
        writeVarint(value.size.toLong())
        output.write(value)
    }

    fun string(number: Int, value: String): ProtoWriter =
        bytes(number, value.toByteArray(Charsets.UTF_8))

    fun build(): ByteArray = output.toByteArray()

    private fun writeVarint(value: Long) {
        var remaining = value
        while (remaining and 0x7f.inv().toLong() != 0L) {
            output.write(((remaining and 0x7f) or 0x80).toInt())
            remaining = remaining ushr 7
        }
        output.write(remaining.toInt())
    }

    private companion object {
        const val LENGTH_DELIMITED = 2
    }
}

internal object ProtoWire {
    private const val MAX_MESSAGE_BYTES = 4 * 1024 * 1024

    fun parse(bytes: ByteArray): List<ProtoField> {
        val fields = ArrayList<ProtoField>()
        var offset = 0
        while (offset < bytes.size) {
            val (tag, tagEnd) = readVarint(bytes, offset)
            offset = tagEnd
            val number = (tag ushr 3).toInt()
            val wireType = (tag and 7).toInt()
            if (number == 0) throw IOException("Invalid protobuf field number.")
            when (wireType) {
                WIRE_VARINT -> {
                    val (value, end) = readVarint(bytes, offset)
                    fields += ProtoField(number, wireType, varint = value)
                    offset = end
                }

                WIRE_FIXED64 -> {
                    offset = checkedEnd(offset, 8, bytes.size)
                    fields += ProtoField(number, wireType)
                }

                WIRE_LENGTH_DELIMITED -> {
                    val (length, dataOffset) = readVarint(bytes, offset)
                    if (length > MAX_MESSAGE_BYTES || length < 0) {
                        throw IOException("Protobuf field is too large.")
                    }
                    val end = checkedEnd(dataOffset, length.toInt(), bytes.size)
                    fields += ProtoField(
                        number = number,
                        wireType = wireType,
                        bytes = bytes.copyOfRange(dataOffset, end),
                    )
                    offset = end
                }

                WIRE_FIXED32 -> {
                    offset = checkedEnd(offset, 4, bytes.size)
                    fields += ProtoField(number, wireType)
                }

                else -> throw IOException("Unsupported protobuf wire type $wireType.")
            }
        }
        return fields
    }

    fun readDelimited(input: InputStream): ByteArray? {
        val firstByte = input.read()
        if (firstByte == -1) return null
        var length = (firstByte and 0x7f).toLong()
        var shift = 7
        var current = firstByte
        var count = 1
        while (current and 0x80 != 0) {
            if (count == 5) throw IOException("Invalid protobuf message length.")
            current = input.read()
            if (current == -1) throw EOFException("Incomplete protobuf message length.")
            length = length or ((current and 0x7f).toLong() shl shift)
            shift += 7
            count += 1
        }
        if (length > MAX_MESSAGE_BYTES) throw IOException("Protobuf message is too large.")
        val payload = ByteArray(length.toInt())
        var offset = 0
        while (offset < payload.size) {
            val read = input.read(payload, offset, payload.size - offset)
            if (read == -1) throw EOFException("Incomplete protobuf message.")
            offset += read
        }
        return payload
    }

    fun writeDelimited(output: OutputStream, message: ByteArray) {
        require(message.size <= MAX_MESSAGE_BYTES)
        var length = message.size
        while (length and 0x7f.inv() != 0) {
            output.write((length and 0x7f) or 0x80)
            length = length ushr 7
        }
        output.write(length)
        output.write(message)
        output.flush()
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var offset = start
        while (offset < bytes.size && shift < 64) {
            val current = bytes[offset++].toInt() and 0xff
            result = result or ((current and 0x7f).toLong() shl shift)
            if (current and 0x80 == 0) return result to offset
            shift += 7
        }
        throw IOException("Invalid or truncated protobuf varint.")
    }

    private fun checkedEnd(start: Int, length: Int, total: Int): Int {
        val end = start.toLong() + length
        if (length < 0 || end > total) throw EOFException("Truncated protobuf field.")
        return end.toInt()
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED32 = 5
}
