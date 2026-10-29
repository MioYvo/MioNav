package io.mionav.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.ParcelUuid
import io.mionav.proto.Chunker
import io.mionav.proto.Frame
import io.mionav.proto.MsgType
import io.mionav.proto.Reassembler
import java.util.UUID
import java.util.concurrent.Executors

/**
 * GATT server on the phone; the watch is the client. One TX (notify) and one RX (write).
 *
 * Chunks are handed to the stack one at a time and only released on
 * `onNotificationSent`, because Android accepts a single in-flight notification per device.
 */
class LinkServer(context: Context) {

    companion object {
        val SERVICE: UUID = UUID.fromString("6D696F4E-0001-4D48-4D49-4F4E4156A000")
        val TX: UUID = UUID.fromString("6D696F4E-0002-4D48-4D49-4F4E4156A000")
        val RX: UUID = UUID.fromString("6D696F4E-0003-4D48-4D49-4F4E4156A000")
        const val MAX_PAYLOAD = 503
    }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()

    private var server: BluetoothGattServer? = null
    private var device: BluetoothDevice? = null
    private var tx: BluetoothGattCharacteristic? = null
    private val queue = ArrayDeque<ByteArray>()
    private val reassembler = Reassembler()

    var onFrame: ((Frame) -> Unit)? = null
    var onConnected: ((String) -> Unit)? = null

    /** True while a watch is attached, so callers do not queue frames into the void. */
    val connected: Boolean
        get() = device != null

    @SuppressLint("MissingPermission")
    fun start() {
        val txChar = BluetoothGattCharacteristic(
            TX,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805F9B34FB"),
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
        }
        val rxChar = BluetoothGattCharacteristic(
            RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(txChar)
            addCharacteristic(rxChar)
        }
        tx = txChar
        server = manager.openGattServer(appContext, callback)?.apply { addService(service) }
        advertise()
    }

    @SuppressLint("MissingPermission")
    private fun advertise() {
        val advertiser = manager.adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE))
            .setIncludeDeviceName(true)
            .build()
        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {}

    @SuppressLint("MissingPermission")
    fun stop() {
        runCatching { manager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { server?.close() }
        server = null
        device = null
        queue.clear()
    }

    /** Queues one message. Large payloads are chunked to the negotiated MTU. */
    fun send(frame: Frame) {
        synchronized(queue) {
            Chunker.chunk(frame, MAX_PAYLOAD).forEach { queue.addLast(it.encode()) }
            drain()
        }
    }

    fun send(type: MsgType, seq: Int, payload: ByteArray, ackRequired: Boolean = false) {
        send(Frame(type, seq, if (ackRequired) io.mionav.proto.Flags.ACK_REQUIRED else 0, payload))
    }

    private fun drain() {
        executor.execute {
            val target = device ?: return@execute
            val char = tx ?: return@execute
            while (true) {
                val bytes = synchronized(queue) { queue.removeFirstOrNull() } ?: return@execute
                char.value = bytes
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @SuppressLint("MissingPermission")
                if (!server!!.notifyCharacteristicChanged(target, char, false)) return@execute
            }
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(dev: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                device = dev
                onConnected?.invoke(dev.address)
            } else {
                device = null
                synchronized(queue) { queue.clear() }
            }
        }

        override fun onMtuChanged(dev: BluetoothDevice, mtu: Int) {
            negotiatedMtu = mtu
        }

        override fun onCharacteristicWriteRequest(
            dev: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (responseNeeded) {
                server?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            if (characteristic.uuid != RX || preparedWrite || offset != 0) return
            val frame = Frame.tryDecode(value ?: return) ?: return
            reassembler.accept(frame)?.let { onFrame?.invoke(it) }
        }

        override fun onNotificationSent(dev: BluetoothDevice, status: Int) {
            drain()
        }
    }

    private var negotiatedMtu: Int = 23
}
