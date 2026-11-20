package com.jurim007.androidtvremote.domain

data class TvDevice(
    val name: String,
    val host: String,
    val port: Int = REMOTE_PORT,
    val serverCertificateFingerprint: String? = null,
) {
    val id: String
        get() = "$host:$port"

    companion object {
        const val REMOTE_PORT = 6466
        const val PAIRING_PORT = 6467
    }
}

enum class TvConnectionPhase {
    DISCONNECTED,
    DISCOVERING,
    CONNECTING,
    PAIRING,
    CONNECTED,
    ERROR,
}

data class TvConnectionState(
    val phase: TvConnectionPhase = TvConnectionPhase.DISCONNECTED,
    val device: TvDevice? = null,
    val message: String? = null,
    val supportedFeatures: Int = 0,
    val pairingReady: Boolean = false,
)
