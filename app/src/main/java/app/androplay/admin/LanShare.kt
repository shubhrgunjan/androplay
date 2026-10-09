package app.androplay.admin

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import app.androplay.protocol.Net
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class LanBoard(val id: String, val name: String, val host: String, val port: Int)

/**
 * Finds AndroPlay boards advertising on the local network (same Wi-Fi or the phone's hotspot).
 * Campus Wi-Fi often blocks this; the admin can still type the IP the board shows on screen.
 */
class LanBrowser(private val ctx: Context) {
    private val found = ConcurrentHashMap<String, LanBoard>()
    private val _boards = MutableStateFlow<List<LanBoard>>(emptyList())
    val boards: StateFlow<List<LanBoard>> = _boards.asStateFlow()

    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null

    fun start() {
        stop()
        val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicast = wifi?.createMulticastLock("androplay-browse")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val manager = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsd = manager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(service: NsdServiceInfo) {
                found.values.removeAll { it.host == service.host?.hostAddress && it.port == service.port }
                publish()
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType != Net.NSD_TYPE && !service.serviceType.contains("androplay")) return
                runCatching {
                    manager.resolveService(
                        service,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                val host = info.host?.hostAddress ?: return
                                val id = info.attributes["id"]?.decodeToString() ?: info.serviceName
                                val name = info.attributes["name"]?.decodeToString() ?: info.serviceName
                                found[id] = LanBoard(id, name, host, if (info.port > 0) info.port else Net.PORT)
                                publish()
                            }
                        },
                    )
                }
            }
        }
        discovery = listener
        runCatching { manager.discoverServices(Net.NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun stop() {
        val manager = nsd
        val listener = discovery
        if (manager != null && listener != null) runCatching { manager.stopServiceDiscovery(listener) }
        nsd = null
        discovery = null
        multicast?.release()
        multicast = null
        found.clear()
        _boards.value = emptyList()
    }

    private fun publish() {
        _boards.value = found.values.sortedBy { it.name }
    }
}

data class LanSendProgress(
    val active: Boolean = false,
    val name: String = "",
    val sent: Long = 0,
    val total: Long = 0,
    val index: Int = 0,
    val count: Int = 0,
    val message: String? = null,
) {
    val fraction: Float get() = if (total <= 0) 0f else (sent.toFloat() / total).coerceIn(0f, 1f)
}
