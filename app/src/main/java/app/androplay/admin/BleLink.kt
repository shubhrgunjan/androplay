package app.androplay.admin

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import app.androplay.protocol.Ble
import app.androplay.protocol.Command
import app.androplay.protocol.DeviceInfo
import app.androplay.protocol.PlaybackState
import app.androplay.protocol.ProtocolJson
import app.androplay.protocol.encode
import app.androplay.protocol.hexToBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * BLE central: finds the board, authenticates, then sends commands as write-without-response
 * (one connection interval, ~10–30 ms) and receives playback state as notifications.
 */
@SuppressLint("MissingPermission")
class BleLink(private val ctx: Context, private val scope: CoroutineScope) {
    enum class Status { OFF, NO_PERMISSION, BLUETOOTH_OFF, SCANNING, CONNECTING, READY, REJECTED }

    private val _status = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _state = MutableStateFlow<PlaybackState?>(null)
    val state: StateFlow<PlaybackState?> = _state.asStateFlow()

    private val _info = MutableStateFlow<DeviceInfo?>(null)
    val info: StateFlow<DeviceInfo?> = _info.asStateFlow()

    private class GattResult(val status: Int, val value: ByteArray? = null)

    private val adapter get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private val ops = Mutex()
    @Volatile private var pending: CompletableDeferred<GattResult>? = null
    private var token: ByteArray = ByteArray(0)
    private var boardId: String? = null
    private val skip = mutableSetOf<String>()
    private var active = false
    private var scanning = false
    private var timer: Job? = null

    fun start(token: String, boardId: String) {
        stop()
        this.token = token.hexToBytes()
        this.boardId = boardId
        active = true
        scan()
    }

    fun stop() {
        active = false
        timer?.cancel()
        stopScan()
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        cmdChar = null
        _status.value = Status.OFF
    }

    /** Returns false when BLE can't carry it right now; the caller falls back to Wi-Fi. */
    suspend fun send(board: String, bytes: ByteArray, confirmed: Boolean): Boolean {
        val g = gatt
        val c = cmdChar
        if (_status.value != Status.READY || boardId != board || g == null || c == null) return false
        if (bytes.size > mtu - 3) return false
        val type = if (confirmed) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return runCatching {
            write(g, c, bytes, type, timeoutMs = 1500) == BluetoothGatt.GATT_SUCCESS
        }.getOrDefault(false)
    }

    fun hasPermissions(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return perms.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun scan() {
        if (!active) return
        if (!hasPermissions()) {
            _status.value = Status.NO_PERMISSION
            return
        }
        val a = adapter
        if (a == null || !a.isEnabled) {
            _status.value = Status.BLUETOOTH_OFF
            retry(5_000)
            return
        }
        val scanner = a.bluetoothLeScanner ?: return retry(5_000)
        _status.value = Status.SCANNING
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(Ble.SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback,
        )
        scanning = true
        // Android throttles apps that start >5 scans per 30 s; restart slowly if nothing is found.
        timer?.cancel()
        timer = scope.launch {
            delay(20_000)
            if (_status.value == Status.SCANNING) {
                stopScan()
                skip.clear()
                scan()
            }
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private fun retry(ms: Long) {
        timer?.cancel()
        timer = scope.launch {
            delay(ms)
            scan()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!active || _status.value != Status.SCANNING || result.device.address in skip) return
            stopScan()
            timer?.cancel()
            _status.value = Status.CONNECTING
            gatt = result.device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            retry(5_000)
        }
    }

    private suspend fun setup(g: BluetoothGatt) {
        try {
            check(op { g.discoverServices() }.status == BluetoothGatt.GATT_SUCCESS)
            val service = g.getService(Ble.SERVICE) ?: error("Board service missing")
            runCatching { op { g.requestMtu(247) } }
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

            val info = ProtocolJson.decodeFromString<DeviceInfo>(read(g, service.getCharacteristic(Ble.INFO)).decodeToString())
            if (info.id != boardId) {
                // A different board in the same room.
                skip += g.device.address
                g.disconnect()
                return
            }
            _info.value = info

            val stateChar = service.getCharacteristic(Ble.STATE)
            g.setCharacteristicNotification(stateChar, true)
            writeDescriptor(g, stateChar.getDescriptor(Ble.CCCD), BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)

            val cmd = service.getCharacteristic(Ble.CMD)
            val auth = write(g, cmd, Command.Auth(token).encode(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            if (auth != BluetoothGatt.GATT_SUCCESS) {
                _status.value = Status.REJECTED
                active = false
                g.disconnect()
                return
            }
            PlaybackState.decode(read(g, stateChar))?.let { _state.value = it }
            cmdChar = cmd
            _status.value = Status.READY
        } catch (e: Exception) {
            g.disconnect()
        }
    }

    private suspend fun op(timeoutMs: Long = 5_000, start: () -> Boolean): GattResult = ops.withLock {
        val d = CompletableDeferred<GattResult>()
        pending = d
        try {
            if (!start()) throw IOException("GATT operation rejected")
            withTimeout(timeoutMs) { d.await() }
        } finally {
            pending = null
        }
    }

    private suspend fun read(g: BluetoothGatt, c: BluetoothGattCharacteristic): ByteArray {
        val r = op { g.readCharacteristic(c) }
        if (r.status != BluetoothGatt.GATT_SUCCESS) throw IOException("Read failed: ${r.status}")
        return r.value ?: ByteArray(0)
    }

    @Suppress("DEPRECATION")
    private suspend fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, type: Int, timeoutMs: Long = 5_000): Int =
        op(timeoutMs) {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = type
                c.value = value
                g.writeCharacteristic(c)
            }
        }.status

    @Suppress("DEPRECATION")
    private suspend fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray) {
        op {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
            } else {
                d.value = value
                g.writeDescriptor(d)
            }
        }
    }

    private fun complete(status: Int, value: ByteArray? = null) {
        pending?.complete(GattResult(status, value))
    }

    private fun onNotify(c: BluetoothGattCharacteristic, value: ByteArray) {
        if (c.uuid == Ble.STATE) PlaybackState.decode(value)?.let { _state.value = it }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                scope.launch { setup(g) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                complete(-1)
                runCatching { g.close() }
                scope.launch {
                    if (gatt !== g) return@launch
                    gatt = null
                    cmdChar = null
                    if (active) {
                        _status.value = Status.SCANNING
                        retry(1_000)
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = complete(status)

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@BleLink.mtu = mtu
            complete(status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            complete(status, value)

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) complete(status, c.value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = complete(status)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = complete(status)

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            onNotify(c, value)

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) onNotify(c, c.value)
        }
    }
}
