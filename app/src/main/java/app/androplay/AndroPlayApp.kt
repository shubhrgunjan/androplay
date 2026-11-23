package app.androplay

import android.app.Application
import android.content.Intent
import app.androplay.admin.AdminRepository
import app.androplay.board.BleStatus
import app.androplay.board.BoardActivity
import app.androplay.board.BoardSync
import app.androplay.board.LanShareServer
import app.androplay.board.LocalStore
import app.androplay.board.PerformanceGuard
import app.androplay.board.PlayerEngine
import app.androplay.board.ScreenKeeper
import app.androplay.board.SyncStatus
import app.androplay.board.Thumbnails
import app.androplay.core.Backend
import app.androplay.core.BackendConfig
import app.androplay.core.Diagnostics
import app.androplay.protocol.ControlRequest
import app.androplay.core.S3
import app.androplay.core.Role
import app.androplay.core.Settings
import app.androplay.core.Slideshow
import app.androplay.core.Updater
import app.androplay.protocol.Command
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AndroPlayApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var settings: Settings
        private set

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    // Board role
    val store by lazy { LocalStore(this) }
    val thumbnails by lazy { Thumbnails(this, store) }
    val engine by lazy { PlayerEngine(this, store, scope) } // first touched on the main thread
    val bleStatus = MutableStateFlow(BleStatus())
    val syncStatus = MutableStateFlow(SyncStatus())
    val boardShows = MutableStateFlow<List<Slideshow>>(emptyList())
    val perf by lazy { PerformanceGuard(this) }
    val screenKeeper by lazy { ScreenKeeper(this) }
    @Volatile var sync: BoardSync? = null
    val lan by lazy { LanShareServer(this) }

    // Admin role
    val admin by lazy { AdminRepository(this) }

    // Both roles
    val diagnostics by lazy { Diagnostics(this, scope) }
    val updater by lazy { Updater(this) }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (settings.role == Role.BOARD) perf.onTrimMemory(level)
    }

    fun newBackend() = Backend(settings.config)

    fun newS3() = newS3For(settings.config)

    fun newS3For(config: BackendConfig) = S3(config, http)

    private val recentControls = LinkedHashMap<String, Pair<Long, Boolean>>()

    fun receive(request: ControlRequest, source: String, acknowledge: (Boolean) -> Unit) {
        scope.launch(Dispatchers.Main.immediate) {
            val now = System.currentTimeMillis()
            if (request.expiresAt < now || request.expiresAt > now + 30_000) {
                diagnostics.record(source, "Command expired or device clock differs")
                acknowledge(false)
                return@launch
            }
            recentControls.entries.removeAll { it.value.first < now }
            recentControls[request.id]?.let { (_, outcome) ->
                acknowledge(outcome)
                return@launch
            }
            if (recentControls.size >= 2048) {
                acknowledge(false)
                return@launch
            }
            val cmd = runCatching {
                request.resolve { id ->
                    store.item(id)?.handle?.takeIf { store.byHandle(it)?.id == id && store.fileOf(id) != null }
                }
            }.getOrNull()
            if (cmd == null || (cmd is Command.Play && store.byHandle(cmd.handle)?.let { store.fileOf(it.id) } == null)) {
                diagnostics.record(source, "Command rejected · content unavailable or invalid")
                acknowledge(false)
                return@launch
            }
            dispatch(cmd, source) { outcome ->
                recentControls[request.id] = request.expiresAt to outcome
                acknowledge(outcome)
            }
        }
    }

    /** Single entry point for every board command source: Redis, BLE, remote keys and touch. */
    fun dispatch(cmd: Command, source: String = "Touch", onHandled: (Boolean) -> Unit = {}) {
        if (cmd is Command.Play && settings.role == Role.BOARD) perf.prepareForPresentation()
        scope.launch(Dispatchers.Main.immediate) {
            val handled = runCatching { engine.handle(cmd) }
                .onFailure { android.util.Log.w("AndroPlay", "Command handling failed", it) }.isSuccess
            val target = if (cmd is Command.Play) " #${cmd.handle}" else ""
            diagnostics.record(source, "${cmd.javaClass.simpleName}$target ${if (handled) "handled" else "failed"}")
            onHandled(handled)
            if (!BoardActivity.visible && engine.needsScreen()) {
                runCatching {
                    startActivity(
                        Intent(this@AndroPlayApp, BoardActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    )
                }
            }
        }
    }
}
