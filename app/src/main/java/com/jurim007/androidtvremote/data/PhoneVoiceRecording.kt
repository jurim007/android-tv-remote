package com.jurim007.androidtvremote.data

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.IOException
import kotlinx.coroutines.delay

private const val SAMPLE_RATE = 8_000
private const val PACKET_BYTES = 8 * 1024
private const val RECORDING_LIMIT_MS = 20_000L

@SuppressLint("MissingPermission")
internal suspend fun recordVoice(
    shouldStop: () -> Boolean,
    onListening: () -> Unit,
    sendAudio: (ByteArray) -> Unit,
) {
    val channelMask = AudioFormat.CHANNEL_IN_MONO
    val encoding = AudioFormat.ENCODING_PCM_16BIT
    val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelMask, encoding)
    if (minBuffer <= 0) throw IOException("The phone could not initialize voice recording.")

    val recorder = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, channelMask, encoding,
        maxOf(minBuffer, PACKET_BYTES * 2),
    )
    val samples = ShortArray(PACKET_BYTES / 2)
    val packet = ByteArray(PACKET_BYTES)
    var count = 0
    try {
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            throw IOException("The phone could not open the microphone.")
        }
        if (shouldStop()) return
        recorder.startRecording()
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IOException("The phone could not start recording.")
        }
        onListening()
        val deadline = SystemClock.elapsedRealtime() + RECORDING_LIMIT_MS
        while (!shouldStop() && SystemClock.elapsedRealtime() < deadline) {
            val read = recorder.read(samples, count, samples.size - count, AudioRecord.READ_NON_BLOCKING)
            if (read < 0) throw IOException("The phone stopped receiving microphone audio.")
            count += read
            if (count == samples.size) {
                encodeVoiceSamples(samples, count, packet)
                sendAudio(packet)
                count = 0
            }
            delay(15)
        }
        if (count > 0) {
            encodeVoiceSamples(samples, count, packet)
            sendAudio(packet)
        }
    } finally {
        try {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        } finally {
            recorder.release()
        }
    }
}

private fun encodeVoiceSamples(samples: ShortArray, count: Int, packet: ByteArray) {
    for (index in 0 until count) {
        val sample = samples[index].toInt()
        packet[index * 2] = sample.toByte()
        packet[index * 2 + 1] = (sample ushr 8).toByte()
    }
    packet.fill(0, fromIndex = count * 2)
}
