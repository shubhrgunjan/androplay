package app.androplay.core

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Local administrator access for settings, the dashboard and board unlock. */
object AdminAuth {
    private const val LEGACY_HASH = "577c38e5efb68923860a8e201d70210b9a7525d1c59df654e246c925d3a877e1"
    private const val ITERATIONS = 210_000
    const val MIN_PASSWORD_LENGTH = 12

    fun check(ctx: Context, username: String, password: String): Boolean {
        val value = "${username.trim().uppercase()}:$password"
        val stored = prefs(ctx).getString("credential", null)
        if (stored == null) {
            val hash = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
            return MessageDigest.isEqual(LEGACY_HASH.encodeToByteArray(), hash.encodeToByteArray())
        }
        return runCatching {
            val fields = stored.split('.')
            val decoder = Base64.getDecoder()
            MessageDigest.isEqual(decoder.decode(fields[1]), derive(value, decoder.decode(fields[0])))
        }.getOrDefault(false)
    }

    fun setCredentials(ctx: Context, username: String, password: String): Boolean {
        require(username.isNotBlank() && ':' !in username && password.length >= MIN_PASSWORD_LENGTH)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val hash = derive("${username.trim().uppercase()}:$password", salt)
        val encoder = Base64.getEncoder()
        return prefs(ctx).edit()
            .putString("credential", "${encoder.encodeToString(salt)}.${encoder.encodeToString(hash)}")
            .commit()
    }

    fun isLoggedIn(ctx: Context): Boolean = prefs(ctx).getBoolean("loggedIn", false)

    fun setLoggedIn(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean("loggedIn", value).apply()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE)

    private fun derive(value: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(value.toCharArray(), salt, ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
