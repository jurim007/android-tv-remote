package com.jurim007.androidtvremote.data

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory

internal class AndroidTvIdentity(context: Context? = null) {
    private val storageDir: File = context?.filesDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")
    private val lock = Any()

    @Volatile
    private var cachedCertificate: X509Certificate? = null

    @Volatile
    private var cachedKeyManagers: Array<KeyManager>? = null

    fun clientCertificate(): X509Certificate = synchronized(lock) {
        cachedCertificate ?: loadOrGenerateIdentity().first.also { cachedCertificate = it }
    }

    fun keyManagers(): Array<KeyManager> = synchronized(lock) {
        cachedKeyManagers ?: loadOrGenerateIdentity().second.also { cachedKeyManagers = it }
    }

    private fun loadOrGenerateIdentity(): Pair<X509Certificate, Array<KeyManager>> {
        cleanupLegacyKeyStore()

        val keyStoreFile = File(storageDir, KEYSTORE_FILENAME)
        if (keyStoreFile.exists()) {
            try {
                val keyStore = KeyStore.getInstance("PKCS12")
                keyStoreFile.inputStream().use { input ->
                    keyStore.load(input, KEYSTORE_PASSWORD)
                }
                val cert = keyStore.getCertificate(ALIAS) as? X509Certificate
                val key = keyStore.getKey(ALIAS, KEYSTORE_PASSWORD)
                if (cert != null && key != null) {
                    cert.checkValidity()
                    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                    kmf.init(keyStore, KEYSTORE_PASSWORD)
                    val km = kmf.keyManagers
                    cachedCertificate = cert
                    cachedKeyManagers = km
                    return cert to km
                }
            } catch (_: Exception) {
                keyStoreFile.delete()
            }
        }

        val now = System.currentTimeMillis()
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(RSA_KEY_SIZE)
        val keyPair = keyPairGenerator.generateKeyPair()

        val notBefore = Date(now - DAY_MILLIS)
        val notAfter = Date(now + CERTIFICATE_VALIDITY_MILLIS)
        val certificate = generateSelfSignedCertificate(keyPair, COMMON_NAME, notBefore, notAfter)

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry(ALIAS, keyPair.private, KEYSTORE_PASSWORD, arrayOf(certificate))

        try {
            val tempFile = File(storageDir, "$KEYSTORE_FILENAME.tmp")
            tempFile.outputStream().use { out ->
                keyStore.store(out, KEYSTORE_PASSWORD)
            }
            if (!tempFile.renameTo(keyStoreFile)) {
                tempFile.copyTo(keyStoreFile, overwrite = true)
                tempFile.delete()
            }
        } catch (_: Exception) {
            // Keep in-memory store if write fails
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, KEYSTORE_PASSWORD)
        val km = kmf.keyManagers

        cachedCertificate = certificate
        cachedKeyManagers = km
        return certificate to km
    }

    private fun cleanupLegacyKeyStore() {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(LEGACY_KEYSTORE_ALIAS)) {
                ks.deleteEntry(LEGACY_KEYSTORE_ALIAS)
            }
        } catch (_: Throwable) {
            // Handled when AndroidKeyStore is unavailable or already clean
        }
    }

    private fun generateSelfSignedCertificate(
        keyPair: KeyPair,
        commonName: String,
        notBefore: Date,
        notAfter: Date,
    ): X509Certificate {
        val sigAlg = DerEncoder.sequence(
            DerEncoder.objectIdentifier("1.2.840.113549.1.1.11"),
            DerEncoder.nullValue(),
        )
        val name = DerEncoder.sequence(
            DerEncoder.set(
                DerEncoder.sequence(
                    DerEncoder.objectIdentifier("2.5.4.3"),
                    DerEncoder.utf8String(commonName),
                ),
            ),
        )
        val validity = DerEncoder.sequence(
            DerEncoder.utcTime(notBefore),
            DerEncoder.utcTime(notAfter),
        )

        val tbs = DerEncoder.sequence(
            DerEncoder.explicit(0, DerEncoder.integer(2)),
            DerEncoder.integer(BigInteger.valueOf(System.currentTimeMillis())),
            sigAlg,
            name,
            validity,
            name,
            keyPair.public.encoded,
        )

        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(keyPair.private)
        sig.update(tbs)
        val signatureBytes = sig.sign()

        val certDer = DerEncoder.sequence(
            tbs,
            sigAlg,
            DerEncoder.bitString(signatureBytes),
        )

        val certFactory = CertificateFactory.getInstance("X.509")
        return certFactory.generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
    }

    private object DerEncoder {
        fun sequence(vararg elements: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            for (el in elements) out.write(el)
            return wrap(0x30, out.toByteArray())
        }

        fun set(vararg elements: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            for (el in elements) out.write(el)
            return wrap(0x31, out.toByteArray())
        }

        fun explicit(tagNumber: Int, content: ByteArray): ByteArray =
            wrap(0xA0 or tagNumber, content)

        fun integer(value: BigInteger): ByteArray = wrap(0x02, value.toByteArray())
        fun integer(value: Long): ByteArray = integer(BigInteger.valueOf(value))
        fun integer(value: Int): ByteArray = integer(BigInteger.valueOf(value.toLong()))

        fun bitString(content: ByteArray): ByteArray {
            val data = ByteArray(content.size + 1)
            data[0] = 0
            System.arraycopy(content, 0, data, 1, content.size)
            return wrap(0x03, data)
        }

        fun nullValue(): ByteArray = byteArrayOf(0x05, 0x00)

        fun objectIdentifier(oid: String): ByteArray {
            val parts = oid.split('.').map(String::toInt)
            val out = ByteArrayOutputStream()
            out.write(parts[0] * 40 + parts[1])
            for (i in 2 until parts.size) {
                writeVarLengthInt(out, parts[i])
            }
            return wrap(0x06, out.toByteArray())
        }

        fun utf8String(str: String): ByteArray =
            wrap(0x0C, str.toByteArray(Charsets.UTF_8))

        fun utcTime(date: Date): ByteArray {
            val sdf = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return wrap(0x17, sdf.format(date).toByteArray(Charsets.US_ASCII))
        }

        private fun writeVarLengthInt(out: OutputStream, value: Int) {
            var num = value
            val stack = ArrayList<Int>()
            stack.add(num and 0x7F)
            num = num ushr 7
            while (num > 0) {
                stack.add((num and 0x7F) or 0x80)
                num = num ushr 7
            }
            for (i in stack.indices.reversed()) {
                out.write(stack[i])
            }
        }

        private fun wrap(tag: Int, content: ByteArray): ByteArray {
            val len = content.size
            val out = ByteArrayOutputStream()
            out.write(tag)
            if (len < 128) {
                out.write(len)
            } else if (len < 256) {
                out.write(0x81)
                out.write(len)
            } else if (len < 65536) {
                out.write(0x82)
                out.write(len ushr 8)
                out.write(len and 0xFF)
            } else {
                out.write(0x83)
                out.write(len ushr 16)
                out.write((len ushr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            out.write(content)
            return out.toByteArray()
        }
    }

    private companion object {
        const val KEYSTORE_FILENAME = "tvcontrol_identity.p12"
        const val ALIAS = "tvcontrol_identity"
        const val LEGACY_KEYSTORE_ALIAS = "tvcontrol_android_tv_remote_identity"
        const val COMMON_NAME = "TVControl"
        const val RSA_KEY_SIZE = 2048
        val KEYSTORE_PASSWORD = "tvcontrol_identity_keystore_pass".toCharArray()
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
        const val CERTIFICATE_VALIDITY_MILLIS = 10L * 365L * DAY_MILLIS
    }
}
