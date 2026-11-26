package com.jurim007.androidtvremote.domain

enum class TvPlatform { ANDROID_TV, WEBOS }

data class TvDevice(
    val name: String,
    val host: String,
    val port: Int = REMOTE_PORT,
    val serverCertificateFingerprint: String? = null,
    val usesUniqueClientIdentity: Boolean = false,
    val platform: TvPlatform = TvPlatform.ANDROID_TV,
    /** Key the webOS TV issued when the connection prompt was accepted. */
    val clientKey: String? = null,
    /** Network adapters of a webOS TV, used to wake it with Wake-on-LAN. */
    val macAddresses: List<String> = emptyList(),
    /** For Android-based TVs: true for Google TV, false for Android TV, null until detected. */
    val isGoogleTv: Boolean? = null,
) {
    val id: String
        get() = "$host:$port"

    companion object {
        const val REMOTE_PORT = 6466
        const val PAIRING_PORT = 6467
        const val WEBOS_PORT = 3001
    }
}

/** An app the connected TV reports as installed. */
data class TvInstalledApp(val id: String, val title: String)

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
    val needsPairing: Boolean = false,
    /** Apps installed on the connected TV, or null when the TV can't report them. */
    val installedApps: List<TvInstalledApp>? = null,
    /** Set once an Android-based TV shows its home screen, which tells Google TV apart. */
    val detectedGoogleTv: Boolean? = null,
)

/** The label for a TV's type badge, or null while an Android-based TV's type is unknown. */
fun tvTypeLabel(tv: TvDevice?, state: TvConnectionState): String? {
    tv ?: return null
    return when (tv.platform) {
        TvPlatform.WEBOS -> "webOS"
        TvPlatform.ANDROID_TV -> when (state.detectedGoogleTv.takeIf { state.device?.id == tv.id } ?: tv.isGoogleTv) {
            true -> "Google TV"
            false -> "Android TV"
            null -> null
        }
    }
}
