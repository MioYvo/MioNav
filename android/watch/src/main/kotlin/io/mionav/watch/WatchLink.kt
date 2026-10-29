package io.mionav.watch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import io.mionav.proto.Frame
import io.mionav.proto.MsgType
import io.mionav.proto.NavFrame
import io.mionav.proto.Reassembler
import io.mionav.proto.Scene
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * GATT client on the watch. Scans for the phone's service, then keeps one connection.
 *
 * The phone owns the connection parameters; the watch only re-connects when the callback says so.
 */
class WatchLink(context: Context) {

    companion object {
        val SERVICE: UUID = UUID.fromString("6D696F4E-0001-4D48-4D49-4F4E4156A000")
        val TX: UUID = UUID.fromString("6D696F4E-0002-4D48-4D49-4F4E4156A000")
        val RX: UUID = UUID.fromString("6D696F4E-0003-4D48-4D49-4F4E4156A000")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager.adapter
    private val reassembler = Reassembler()

    private val _nav = MutableStateFlow<NavFrame?>(null)
    val nav: StateFlow<NavFrame?> = _nav

    private val _scene = MutableStateFlow<Scene?>(null)
    val scene: StateFlow<Scene?> = _scene

    private val _roads = MutableStateFlow<Map<Int, String>>(emptyMap())
    val roads: StateFlow<Map<Int, String>> = _roads

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var scanning = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (adapter?.isEnabled != true || scanning) return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        adapter.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning) return
        scanning = false
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun close() {
        stop()
        gatt?.close()
        gatt = null
        _connected.value = false
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            stop()
            gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connected.value = true
                    g.requestMtu(517)
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connected.value = false
                    g.close()
                    gatt = null
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE) ?: return
            val tx = service.getCharacteristic(TX) ?: return
            rx = service.getCharacteristic(RX)
            g.setCharacteristicNotification(tx, true)
            tx.getDescriptor(CCCD)?.let { descriptor ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(descriptor)
                }
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handle(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            characteristic.value?.let { handle(it) }
        }
    }

    private fun handle(bytes: ByteArray) {
        val frame = Frame.tryDecode(bytes) ?: return
        val whole = reassembler.accept(frame) ?: return
        when (whole.type) {
            MsgType.NAV -> _nav.value = NavFrame.decode(whole.payload)
            MsgType.SCENE -> _scene.value = runCatching { Scene.decode(whole.payload) }.getOrNull()
            MsgType.STR -> {
                val entries = runCatching { io.mionav.proto.StringTable.decode(whole.payload) }.getOrNull() ?: return
                _roads.value = _roads.value + entries.associate { it.id to it.text }
            }
            MsgType.HELLO -> ack(whole.seq)
            else -> Unit
        }
    }

    @SuppressLint("MissingPermission")
    private fun ack(seq: Int) {
        val g = gatt ?: return
        val char = rx ?: return
        val payload = byteArrayOf((seq and 0xFF).toByte(), ((seq shr 8) and 0xFF).toByte())
        @Suppress("DEPRECATION")
        char.value = Frame(MsgType.ACK, seq = 0, payload = payload).encode()
        g.writeCharacteristic(char)
    }
}
