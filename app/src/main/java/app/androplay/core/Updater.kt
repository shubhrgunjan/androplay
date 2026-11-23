package app.androplay.core

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.IntentCompat
import app.androplay.AndroPlayApp
import app.androplay.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** A published build, announced in Redis (`ap:release`) by scripts/publish_update.py. */
data class ReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    val objectKey: String,
    val sha256: String,
    val size: Long,
    val notes: String,
)

sealed interface UpdateState {
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateState
    data class Installing(val release: ReleaseInfo) : UpdateState
    /** Android is showing its "update this app?" prompt on this device. */
    data class Confirming(val release: ReleaseInfo) : UpdateState
    data class Failed(val message: String, val release: ReleaseInfo? = null) : UpdateState
}

/**
 * In-app updates without GitHub: the APK lives in the content bucket, the latest version is a
 * Redis hash, and a stream event (`op=release`) makes every device check immediately.
 * Installs through PackageInstaller; on Android 12+ updates after the first are silent.
 */
class Updater(private val app: AndroPlayApp) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.UpToDate)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val mutex = Mutex()

    fun canInstall(): Boolean = app.packageManager.canRequestPackageInstalls()

    /** Returns the newer release, if any, and publishes it as [UpdateState.Available]. */
    suspend fun check(): ReleaseInfo? = withContext(Dispatchers.IO) {
        if (!app.settings.config.isComplete) return@withContext null
        val release = runCatching { app.newBackend().use { it.latestRelease() } }
            .onFailure { Log.w(TAG, "Update check failed", it) }
            .getOrNull()
        if (release != null && release.versionCode > BuildConfig.VERSION_CODE) {
            if (!busy) {
                _state.value = UpdateState.Available(release)
            }
            release
        } else {
            if (_state.value is UpdateState.Available) _state.value = UpdateState.UpToDate
            null
        }
    }

    /** Downloads, verifies and hands the APK to the system installer. */
    suspend fun install(release: ReleaseInfo) = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val apk = download(release)
                _state.value = UpdateState.Installing(release)
                commit(apk)
            } catch (e: Exception) {
                Log.w(TAG, "Update failed", e)
                _state.value = UpdateState.Failed(e.message ?: e.javaClass.simpleName, release)
            }
        }
    }

    /** Short human-readable status, reported by boards in their heartbeat; null when up to date. */
    fun statusText(): String? = when (val s = _state.value) {
        UpdateState.UpToDate -> null
        is UpdateState.Available -> "Update v${s.release.versionName} available"
        is UpdateState.Downloading -> "Downloading v${s.release.versionName} ${(s.progress * 100).toInt()}%"
        is UpdateState.Installing -> "Installing v${s.release.versionName}"
        is UpdateState.Confirming -> "Tap Update on the board to install v${s.release.versionName}"
        is UpdateState.Failed -> "Update failed: ${s.message}"
    }

    val busy: Boolean get() = _state.value is UpdateState.Downloading || _state.value is UpdateState.Installing || _state.value is UpdateState.Confirming

    fun onNeedsConfirmation() {
        val s = _state.value
        if (s is UpdateState.Installing) _state.value = UpdateState.Confirming(s.release)
    }

    fun onInstallFailed(message: String?) {
        _state.value = UpdateState.Failed(message ?: "Install failed")
    }

    private fun download(release: ReleaseInfo): File {
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "androplay-${release.versionCode}.apk")
        if (apk.length() == release.size && sha256(apk) == release.sha256) return apk
        dir.listFiles()?.forEach { it.delete() }
        _state.value = UpdateState.Downloading(release, 0f)
        app.newS3().get(release.objectKey).use { response ->
            val body = response.body ?: error("Empty download")
            apk.outputStream().use { out ->
                val input = body.byteStream()
                val buf = ByteArray(256 * 1024)
                var done = 0L
                var last = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - last > 1_000_000) {
                        last = done
                        _state.value = UpdateState.Downloading(release, done / release.size.coerceAtLeast(1).toFloat())
                    }
                }
            }
        }
        if (sha256(apk) != release.sha256) {
            apk.delete()
            error("Downloaded update is corrupt (checksum mismatch)")
        }
        return apk
    }

    private fun commit(apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out, 256 * 1024)
                    session.fsync(out)
                }
            }
            val callback = PendingIntent.getBroadcast(
                app, id, Intent(app, UpdateReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "Updater"
    }
}

/** Result of a PackageInstaller session: asks for confirmation when Android requires it. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as AndroPlayApp
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                app.updater.onNeedsConfirmation()
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let {
                    runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { app.updater.onInstallFailed("Open AndroPlay to confirm the update") }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the system restarts us (MY_PACKAGE_REPLACED)
            else -> app.updater.onInstallFailed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed ($status)")
        }
    }
}
