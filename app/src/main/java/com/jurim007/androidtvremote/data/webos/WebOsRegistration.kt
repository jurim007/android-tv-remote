package com.jurim007.androidtvremote.data.webos

import org.json.JSONArray
import org.json.JSONObject

/**
 * The registration request webOS TVs expect from second-screen remotes. It mirrors the
 * manifest used by LG's remote app and open-source clients such as aiowebostv.
 */
internal object WebOsRegistration {
    const val MESSAGE_ID = "register_0"

    fun message(clientKey: String?, signed: Boolean): JSONObject {
        val manifest = JSONObject()
            .put("manifestVersion", 1)
            .put("appVersion", "1.1")
        if (signed) {
            manifest
                .put("signed", signedSection())
                .put("permissions", JSONArray(PERMISSIONS))
                .put(
                    "signatures",
                    JSONArray().put(JSONObject().put("signatureVersion", 1).put("signature", SIGNATURE)),
                )
        } else {
            // Newer firmware rejects the signed manifest; the unsigned form lists every permission.
            manifest.put("permissions", JSONArray((PERMISSIONS + SIGNED_PERMISSIONS).distinct().sorted()))
        }
        val payload = JSONObject()
            .put("forcePairing", false)
            .put("pairingType", "PROMPT")
            .put("manifest", manifest)
        if (clientKey != null) payload.put("client-key", clientKey)
        return JSONObject().put("type", "register").put("id", MESSAGE_ID).put("payload", payload)
    }

    private fun signedSection() = JSONObject()
        .put("created", "20140509")
        .put("appId", "com.lge.test")
        .put("vendorId", "com.lge")
        .put(
            "localizedAppNames",
            JSONObject().put("", "LG Remote App").put("ko-KR", "리모컨 앱").put("zxx-XX", "ЛГ Rэмotэ AПП"),
        )
        .put("localizedVendorNames", JSONObject().put("", "LG Electronics"))
        .put("permissions", JSONArray(SIGNED_PERMISSIONS))
        .put("serial", "2f930e2d2cfe083771f68e4fe7bb07")

    private const val SIGNATURE =
        "eyJhbGdvcml0aG0iOiJSU0EtU0hBMjU2Iiwia2V5SWQiOiJ0ZXN0LXNpZ25pbm" +
            "ctY2VydCIsInNpZ25hdHVyZVZlcnNpb24iOjF9.hrVRgjCwXVvE2OOSpDZ58hR" +
            "+59aFNwYDyjQgKk3auukd7pcegmE2CzPCa0bJ0ZsRAcKkCTJrWo5iDzNhMBWRy" +
            "aMOv5zWSrthlf7G128qvIlpMT0YNY+n/FaOHE73uLrS/g7swl3/qH/BGFG2Hu4" +
            "RlL48eb3lLKqTt2xKHdCs6Cd4RMfJPYnzgvI4BNrFUKsjkcu+WD4OO2A27Pq1n" +
            "50cMchmcaXadJhGrOqH5YmHdOCj5NSHzJYrsW0HPlpuAx/ECMeIZYDh6RMqaFM" +
            "2DXzdKX9NmmyqzJ3o/0lkk/N97gfVRLW5hA29yeAwaCViZNCP8iC9aO0q9fQoj" +
            "oa7NQnAtw=="

    private val PERMISSIONS = listOf(
        "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE", "TEST_OPEN", "TEST_PROTECTED",
        "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_RECORDING",
        "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_TV", "CONTROL_POWER", "CONTROL_TV_SCREEN",
        "READ_APP_STATUS", "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST", "READ_NETWORK_STATE",
        "READ_RUNNING_APPS", "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_TOAST", "READ_POWER_STATE",
        "READ_COUNTRY_INFO", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD", "READ_INSTALLED_APPS",
        "READ_SETTINGS",
    )

    private val SIGNED_PERMISSIONS = listOf(
        "TEST_SECURE", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD", "READ_INSTALLED_APPS",
        "READ_LGE_SDX", "READ_NOTIFICATIONS", "SEARCH", "WRITE_SETTINGS", "WRITE_NOTIFICATION_ALERT",
        "CONTROL_POWER", "READ_CURRENT_CHANNEL", "READ_RUNNING_APPS", "READ_UPDATE_INFO",
        "UPDATE_FROM_REMOTE_APP", "READ_LGE_TV_INPUT_EVENTS", "READ_TV_CURRENT_TIME",
    )
}
