package app.androplay.board

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import app.androplay.AndroPlayApp
import app.androplay.protocol.Ble
import app.androplay.protocol.DeviceInfo
import app.androplay.protocol.Net
import app.androplay.protocol.toHex
import app.androplay.protocol.Command
import app.androplay.protocol.PlaybackState
import app.androplay.protocol.ProtocolJson
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * GATT server: phones write [Command]s to CMD and get [PlaybackState] notifications on STATE.
 * A connection must first write [Command.Auth] with the board's BLE token (shared via Redis) before other commands count.
 */
@SuppressLint("MissingPermission")
class BleServer(private val ctx: Context, private val app: AndroPlayApp) {
    private val manager = ctx.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private lateinit var stateChar: BluetoothGattCharacteristic
    private val subscribers = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val authorized = ConcurrentHashMap.newKeySet<String>()
    private val connected = ConcurrentHashMap.newKeySet<String>()
    private val infoCache = ConcurrentHashMap<String, ByteArray>()
    private var lastSent: PlaybackState? = null
    private var stateJob: Job? = null

    fun hasPermissions(): Boolean = listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        .all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun start() {
        if (server != null) return
        if (!hasPermissions()) return setStatus(error = "Bluetooth permission not granted")
        val adapter = manager?.adapter ?: return setStatus(error = "No Bluetooth adapter")
        if (!adapter.isEnabled) return setStatus(error = "Bluetooth is off")
        if (adapter.bluetoothLeAdvertiser == null) return setStatus(error = "BLE advertising not supported")

        val gatt = manager.openGattServer(ctx, callback) ?: return setStatus(error = "Could not open GATT server")
        server = gatt
        val service = BluetoothGattService(Ble.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                Ble.CMD,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )
        stateChar = BluetoothGattCharacteristic(
            Ble.STATE,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    Ble.CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
        }
        service.addCharacteristic(stateChar)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                Ble.INFO,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            ),
        )
        gatt.addService(service) // advertising starts in onServiceAdded

        stateJob = app.scope.launch { app.engine.state.collect { notifyState(it) } }
    }

    fun stop() {
        stateJob?.cancel()
        runCatching { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { server?.close() }
        server = null
        subscribers.clear()
        authorized.clear()
        connected.clear()
        setStatus(advertising = false)
    }

    private fun startAdvertising() {
        val advertiser = manager?.adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(Ble.SERVICE))
            .setIncludeDeviceName(false)
            .build()
        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) = setStatus(advertising = true)
        override fun onStartFailure(errorCode: Int) = setStatus(error = "Advertising failed ($errorCode)")
    }

    private fun notifyState(s: PlaybackState) {
        val gatt = server ?: return
        val last = lastSent
        val significant = last == null || last.copy(positionMs = s.positionMs) != s ||
            abs(last.positionMs - s.positionMs) >= 1000
        if (!significant || subscribers.isEmpty()) return
        lastSent = s
        val value = s.encode()
        for (device in subscribers) {
            runCatching { gatt.notifyCharacteristicChanged(device, stateChar, false, value) }
        }
    }

    private fun setStatus(advertising: Boolean = app.bleStatus.value.advertising, error: String? = null) {
        app.bleStatus.value = BleStatus(advertising, connected.size, error)
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising() else setStatus(error = "GATT service failed ($status)")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected += device.address
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected -= device.address
                subscribers.remove(device)
                authorized -= device.address
                infoCache -= device.address
            }
            setStatus()
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            val value = when (characteristic.uuid) {
                Ble.STATE -> app.engine.state.value.encode()
                // Long reads arrive in several requests; serve them from one consistent snapshot.
                Ble.INFO -> if (offset == 0) {
                    ProtocolJson.encodeToString(DeviceInfo(app.settings.deviceId, app.settings.deviceName, NetUtil.localIp(ctx) ?: "", Net.PORT)).encodeToByteArray().also { infoCache[device.address] = it }
                } else {
                    infoCache[device.address]
                }
                else -> null
            }
            val gatt = server ?: return
            when {
                value == null -> gatt.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                offset > value.size -> gatt.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                else -> gatt.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            var ok = false
            if (characteristic.uuid == Ble.CMD && value != null) {
                val cmd = Command.decode(value)
                ok = when {
                    cmd is Command.Auth -> (cmd.token.toHex() == app.settings.bleToken).also { if (it) authorized += device.address }
                    cmd != null && device.address in authorized -> {
                        app.dispatch(cmd)
                        true
                    }
                    else -> false
                }
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else GATT_WRITE_NOT_PERMITTED, 0, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (descriptor.uuid == Ble.CCCD) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) subscribers += device
                else subscribers -= device
                lastSent = null
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }

    private companion object {
        const val GATT_WRITE_NOT_PERMITTED = 0x03
    }
}
