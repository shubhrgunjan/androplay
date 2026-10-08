package app.androplay.core

import android.content.Context
import java.security.MessageDigest

/**
 * Lab admin login. One fixed account; only a SHA-256 of "user:password" is kept in the app.
 * Guards: the Admin dashboard, settings/role changes on any device, and unlocking the board.
 */
object AdminAuth {
    private const val CREDENTIAL_HASH = "577c38e5efb68923860a8e201d70210b9a7525d1c59df654e246c925d3a877e1"

    fun check(username: String, password: String): Boolean =
        sha256("${username.trim().uppercase()}:$password") == CREDENTIAL_HASH

    fun isLoggedIn(ctx: Context): Boolean = prefs(ctx).getBoolean("loggedIn", false)

    fun setLoggedIn(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean("loggedIn", value).apply()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE)

    private fun sha256(s: String) =
        MessageDigest.getInstance("SHA-256").digest(s.encodeToByteArray()).joinToString("") { "%02x".format(it) }
}
