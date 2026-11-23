package app.androplay.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import okhttp3.OkHttpClient
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/** The board advertises its certificate fingerprint over the authenticated cloud connection. */
object LanTls {
    private const val ALIAS = "androplay-lan"
    private const val KEYSTORE = "AndroidKeyStore"

    data class Server(val context: SSLContext, val pin: String)

    @Synchronized
    fun server(): Server {
        val keys = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (!keys.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE).apply {
                initialize(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT)
                        .setCertificateSubject(X500Principal("CN=AndroPlay LAN"))
                        .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                        .build(),
                )
                generateKeyPair()
            }
        }
        val key = keys.getKey(ALIAS, null) as PrivateKey
        val cert = keys.getCertificate(ALIAS) as X509Certificate
        val manager = object : X509KeyManager {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = null
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) =
                if (keyType == "RSA") arrayOf(ALIAS) else null

            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) =
                getServerAliases(keyType, issuers)?.firstOrNull()

            override fun getCertificateChain(alias: String?) = if (alias == ALIAS) arrayOf(cert) else null
            override fun getPrivateKey(alias: String?) = if (alias == ALIAS) key else null
        }
        val context = SSLContext.getInstance("TLS").apply { init(arrayOf(manager), null, null) }
        return Server(context, fingerprint(cert))
    }

    fun client(builder: OkHttpClient.Builder, pin: String): OkHttpClient.Builder {
        require(pin.length == 64 && pin.all { it in '0'..'9' || it in 'a'..'f' })
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                throw CertificateException("Client certificates are not used")

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val cert = chain?.firstOrNull() ?: throw CertificateException("Missing board certificate")
                cert.checkValidity()
                if (fingerprint(cert) != pin) throw CertificateException("Board certificate does not match")
            }
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        // Boards change IP addresses. The exact pinned certificate identifies the board.
        return builder.sslSocketFactory(context.socketFactory, trust)
            .hostnameVerifier { _, session ->
                runCatching { fingerprint(session.peerCertificates.first() as X509Certificate) == pin }.getOrDefault(false)
            }
            .followRedirects(false)
            .followSslRedirects(false)
    }

    private fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
}
