package app.androplay.board

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.androplay.AndroPlayApp
import app.androplay.core.Role
import java.net.Inet4Address
import java.net.NetworkInterface

data class BleStatus(val advertising: Boolean = false, val clients: Int = 0, val error: String? = null)

/** Always-on foreground service for the Board role: Redis sync, downloads, commands, BLE. */
class BoardService : Service() {
    private lateinit var app: AndroPlayApp
    private var ble: BleServer? = null
    private var sync: BoardSync? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> restartBle()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> ble?.stop()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        app = application as AndroPlayApp
        ServiceCompat.startForeground(this, 1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        app.perf.start()
        app.screenKeeper.start()
        startSync()
        restartBle()
        wifiLock = getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "androplay")
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }
        ContextCompat.registerReceiver(
            this, bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESTART_BLE -> restartBle()
            ACTION_RESTART_SYNC -> startSync()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(bluetoothReceiver)
        sync?.stop()
        app.sync = null
        ble?.stop()
        wifiLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSync() {
        sync?.stop()
        sync = null
        app.sync = null
        if (!app.settings.config.isComplete) {
            app.syncStatus.value = SyncStatus(error = "Backend not configured")
            return
        }
        try {
            sync = BoardSync(app, app.newBackend(), app.newS3(), app.store).also {
                app.sync = it
                it.start()
            }
        } catch (e: Exception) {
            app.syncStatus.value = SyncStatus(error = "Bad backend config: ${e.message}")
        }
    }

    private fun restartBle() {
        ble?.stop()
        ble = BleServer(this, app).also { it.start() }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Board", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, BoardActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("AndroPlay board is ready")
            .setContentText("Syncing content and listening for remotes")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "board"
        private const val ACTION_RESTART_BLE = "app.androplay.RESTART_BLE"
        private const val ACTION_RESTART_SYNC = "app.androplay.RESTART_SYNC"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, BoardService::class.java))
        }

        fun restartBle(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, BoardService::class.java).setAction(ACTION_RESTART_BLE))
        }

        fun restartSync(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, BoardService::class.java).setAction(ACTION_RESTART_SYNC))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as AndroPlayApp
        if (app.settings.role != Role.BOARD) return
        BoardService.start(context)
        runCatching {
            context.startActivity(Intent(context, BoardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

object NetUtil {
    fun localIp(ctx: Context): String? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm?.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.let { return it.hostAddress }
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
