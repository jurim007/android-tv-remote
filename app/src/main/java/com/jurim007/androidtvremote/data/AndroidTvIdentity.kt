package com.jurim007.androidtvremote.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.security.auth.x500.X500Principal

internal class AndroidTvIdentity {
    fun clientCertificate(): X509Certificate {
        val keyStore = loadKeyStore()
        val existing = keyStore.getCertificate(ALIAS) as? X509Certificate
        if (existing != null) return existing

        val now = System.currentTimeMillis()
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA,
            ANDROID_KEY_STORE,
        )
        generator.initialize(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=TVControl"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(Date(now - DAY_MILLIS))
                .setCertificateNotAfter(Date(now + CERTIFICATE_VALIDITY_MILLIS))
                .setRandomizedEncryptionRequired(false)
                .build(),
        )
        generator.generateKeyPair()
        return (loadKeyStore().getCertificate(ALIAS) as? X509Certificate)
            ?: throw KeyStoreException("Android Keystore did not create the TVControl certificate.")
    }

    fun keyManagers(): Array<KeyManager> {
        clientCertificate()
        val keyStore = loadKeyStore()
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore, null)
        return factory.keyManagers
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val ALIAS = "tvcontrol_android_tv_remote_identity"
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
        const val CERTIFICATE_VALIDITY_MILLIS = 10L * 365L * DAY_MILLIS
    }
}
