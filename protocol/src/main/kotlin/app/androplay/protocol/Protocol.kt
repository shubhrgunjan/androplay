package app.androplay.protocol

import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * BLE GATT layout exposed by the board. BLE is the low-latency control channel only;
 * file transfer always goes over Wi-Fi (see [Net]).
 */
object Ble {
    val SERVICE: UUID = UUID.fromString("a7e10000-5c3b-4d2a-9f6e-2b8c1d0e4f70")

    /** Write (with or without response): one encoded [Command] per write. */
    val CMD: UUID = UUID.fromString("a7e10001-5c3b-4d2a-9f6e-2b8c1d0e4f70")

    /** Read + notify: encoded [PlaybackState], 20 bytes so it fits the default MTU. */
    val STATE: UUID = UUID.fromString("a7e10002-5c3b-4d2a-9f6e-2b8c1d0e4f70")

    /** Read: JSON [DeviceInfo], so a phone learns the board's Wi-Fi address over BLE. */
    val INFO: UUID = UUID.fromString("a7e10003-5c3b-4d2a-9f6e-2b8c1d0e4f70")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

object Net {
    const val PORT = 8765
    const val NSD_TYPE = "_androplay._tcp"
    const val TOKEN_HEADER = "X-AndroPlay-Token"
    const val API_VERSION = 1
}

val ProtocolJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Odd hex length" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
