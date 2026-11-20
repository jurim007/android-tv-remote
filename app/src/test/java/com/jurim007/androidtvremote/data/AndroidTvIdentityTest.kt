package com.jurim007.androidtvremote.data

import java.io.File
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidTvIdentityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun generatesAndPersistsValidRsaCertificate() {
        val identity = AndroidTvIdentity(context = null)
        val cert = identity.clientCertificate()

        assertNotNull(cert)
        cert.checkValidity()
        assertEquals("CN=TVControl", cert.subjectX500Principal.name)
        assertTrue(cert.publicKey is RSAPublicKey)
        val rsaKey = cert.publicKey as RSAPublicKey
        assertEquals(2048, rsaKey.modulus.bitLength())

        // Multiple calls return the same cached certificate
        val cert2 = identity.clientCertificate()
        assertEquals(cert, cert2)

        val keyManagers = identity.keyManagers()
        assertNotNull(keyManagers)
        assertTrue(keyManagers.isNotEmpty())
    }

    @Test
    fun executesMutualTlsHandshakeSuccessfully() {
        val identity = AndroidTvIdentity(context = null)
        val clientKeyManagers = identity.keyManagers()
        val clientCert = identity.clientCertificate()

        var serverReceivedClientCert: X509Certificate? = null

        val serverTrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                assertNotNull(chain)
                assertTrue(chain!!.isNotEmpty())
                serverReceivedClientCert = chain[0]
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(clientCert)
        }

        val clientTrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val serverContext = SSLContext.getInstance("TLS")
        serverContext.init(clientKeyManagers, arrayOf(serverTrustManager), null)

        val serverSocket = serverContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        serverSocket.needClientAuth = true
        val port = serverSocket.localPort

        val clientContext = SSLContext.getInstance("TLS")
        clientContext.init(clientKeyManagers, arrayOf(clientTrustManager), null)

        val serverThread = Thread {
            val socket = serverSocket.accept() as SSLSocket
            socket.startHandshake()
            socket.close()
            serverSocket.close()
        }
        serverThread.start()

        val clientSocket = clientContext.socketFactory.createSocket("localhost", port) as SSLSocket
        clientSocket.startHandshake()
        clientSocket.close()

        serverThread.join(5000)

        assertNotNull(serverReceivedClientCert)
        assertEquals(clientCert.subjectX500Principal.name, serverReceivedClientCert?.subjectX500Principal?.name)
        assertEquals(clientCert.publicKey, serverReceivedClientCert?.publicKey)
    }
}
