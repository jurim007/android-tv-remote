package com.jurim007.androidtvremote.data

import com.jurim007.androidtvremote.domain.RemoteOption
import com.jurim007.androidtvremote.ui.automaticDownloadEnabled
import com.jurim007.androidtvremote.ui.automaticInstallEnabled
import java.security.KeyPairGenerator
import java.security.Signature
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateVersionTest {
    @Test
    fun newerStableVersionsAreComparedNumerically() {
        assertTrue(isNewerRelease("v1.0.1", "1.0.0"))
        assertTrue(isNewerRelease("1.10.0", "1.9.9"))
        assertTrue(isNewerRelease("2.0.0", "1.99.99"))
    }

    @Test
    fun equalOlderPrereleaseAndMalformedVersionsAreIgnored() {
        assertFalse(isNewerRelease("v1.0.0", "1.0.0"))
        assertFalse(isNewerRelease("1.9.99", "1.10.0"))
        assertFalse(isNewerRelease("2.0.0-beta.1", "1.0.0"))
        assertFalse(isNewerRelease("2.0", "1.0.0"))
        assertFalse(isNewerRelease("2.00.0", "1.0.0"))
    }

    @Test
    fun automaticActionsRequireTheirParentOptions() {
        val master = RemoteOption.AUTOMATIC_UPDATE_CHECKS
        val download = RemoteOption.AUTOMATIC_UPDATE_DOWNLOADS
        val install = RemoteOption.AUTOMATIC_UPDATE_INSTALLS
        assertFalse(automaticDownloadEnabled(RemoteOption.defaults))
        assertFalse(automaticInstallEnabled(RemoteOption.defaults))
        assertFalse(automaticDownloadEnabled(setOf(download, install)))
        assertFalse(automaticInstallEnabled(setOf(master, install)))
        assertTrue(automaticDownloadEnabled(setOf(master, download)))
        assertFalse(automaticInstallEnabled(setOf(master, download)))
        assertTrue(automaticInstallEnabled(setOf(master, download, install)))
    }

    @Test
    fun checksumSignatureBindsToThePackageReleaseSizeAndDigest() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val installed = keys.generateKeyPair()
        val attacker = keys.generateKeyPair()
        val release = AppUpdate("1.4.0", "https://github.com/fixture.apk", 256, null)
        val checksum = "a".repeat(64)
        val payload = updateIntegrityPayload("com.example.app", release, checksum)
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(installed.private)
            update(payload)
            sign()
        }
        assertTrue(verifyUpdateIntegrity(installed.public, payload, signature))
        assertFalse(verifyUpdateIntegrity(attacker.public, payload, signature))
        assertFalse(verifyUpdateIntegrity(installed.public,
            updateIntegrityPayload("com.example.other", release, checksum), signature))
        assertFalse(verifyUpdateIntegrity(installed.public,
            updateIntegrityPayload("com.example.app", release.copy(version = "1.4.1"), checksum), signature))
        assertFalse(verifyUpdateIntegrity(installed.public,
            updateIntegrityPayload("com.example.app", release.copy(size = 257), checksum), signature))
        assertFalse(verifyUpdateIntegrity(installed.public,
            updateIntegrityPayload("com.example.app", release, "b".repeat(64)), signature))
    }
}
