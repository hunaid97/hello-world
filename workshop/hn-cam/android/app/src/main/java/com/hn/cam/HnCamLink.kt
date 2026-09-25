package com.hn.cam

import android.annotation.SuppressLint
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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Finds the HN_CAM board over Bluetooth LE, keeps it connected and turns its
 * notification byte stream back into JPEG frames and photos.
 *
 * Wire format (see hn_cam.ino): 4-byte magic + uint32 little-endian length + JPEG.
 * "XCAM" is a live frame, "XSNP" a photo. Writing 'P' to CTRL asks for a photo.
 */
@SuppressLint("MissingPermission") // MainActivity only calls start() once permissions are granted.
class HnCamLink(private val context: Context) {

    companion object {
        val SERVICE: UUID = UUID.fromString("7a1e0001-3c4b-4d8e-9f60-4e8c1a2b3c4d")
        val DATA: UUID = UUID.fromString("7a1e0002-3c4b-4d8e-9f60-4e8c1a2b3c4d")
        val CTRL: UUID = UUID.fromString("7a1e0003-3c4b-4d8e-9f60-4e8c1a2b3c4d")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val _status = MutableStateFlow("Starting")
    val status: StateFlow<String> = _status
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    private val _frame = MutableStateFlow<Bitmap?>(null)
    val frame: StateFlow<Bitmap?> = _frame
    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps

    /** Called on the Bluetooth thread with each photo's JPEG bytes. */
    var onPhoto: ((ByteArray) -> Unit)? = null

    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val thread = HandlerThread("hn-cam-ble").apply { start() }
    private val handler = Handler(thread.looper)
    private var gatt: BluetoothGatt? = null
    private var ctrl: BluetoothGattCharacteristic? = null
    private var running = false
    private var scanning = false

    private val parser = PacketParser(
        onPacket = { photo, jpeg -> if (photo) onPhoto?.invoke(jpeg) else showFrame(jpeg) },
        onPhotoProgress = { percent -> _status.value = "Receiving photo $percent%" },
    )
    private var framesThisSecond = 0
    private var secondStart = 0L

    fun start() = handler.post {
        if (running) return@post
        running = true
        scan()
    }

    fun stop() = handler.post {
        running = false
        stopScan()
        gatt?.close()
        gatt = null
        _connected.value = false
        _status.value = "Stopped"
    }

    /** Asks the board for a photo; it arrives through [onPhoto]. */
    fun requestPhoto(): Boolean {
        val g = gatt ?: return false
        val c = ctrl ?: return false
        return g.writeCharacteristic(c, byteArrayOf('P'.code.toByte()),
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
    }

    // ---- Scanning ----

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post {
                if (!scanning) return@post
                stopScan()
                connect(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                scanning = false
                _status.value = "Bluetooth scan failed ($errorCode)"
                retryLater()
            }
        }
    }

    private fun scan() {
        if (!running || scanning || gatt != null) return
        val scanner = adapter?.bluetoothLeScanner
        if (adapter?.isEnabled != true || scanner == null) {
            _status.value = "Turn on Bluetooth"
            retryLater()
            return
        }
        _status.value = "Searching for HN_CAM"
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        scanning = true
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: IllegalStateException) {}
    }

    private fun retryLater() = handler.postDelayed({ scan() }, 1500)

    // ---- Connection ----

    private fun connect(device: BluetoothDevice) {
        // HN_CAM doesn't use pairing. A leftover bond (Chrome's "Pair" creates one) makes the
        // phone try to encrypt with a key the board lost at its last reboot, and every
        // connection then times out. removeBond() is hidden API, so reach it by reflection.
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            val removed = runCatching { device.javaClass.getMethod("removeBond").invoke(device) as Boolean }
            Log.d("HnCam", "removing stale bond: $removed")
            if (removed.getOrNull() != true) {
                _status.value = "Forget HN_CAM in Bluetooth settings"
                retryLater()
                return
            }
        }
        _status.value = "Connecting"
        parser.reset()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_2M_MASK, handler)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Short connection interval and big packets: both matter a lot for throughput.
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.requestMtu(517)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                g.close()
                if (gatt == g) gatt = null
                ctrl = null
                _connected.value = false
                _fps.value = 0
                if (running) {
                    _status.value = "Lost HN_CAM, searching again"
                    retryLater()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE)
            val data = service?.getCharacteristic(DATA)
            val cccd = data?.getDescriptor(CCCD)
            if (data == null || cccd == null) {
                _status.value = "HN_CAM firmware is out of date"
                g.disconnect()
                return
            }
            ctrl = service.getCharacteristic(CTRL)
            g.setCharacteristicNotification(data, true)
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            _connected.value = status == BluetoothGatt.GATT_SUCCESS
            _status.value = if (_connected.value) "Connected" else "Couldn't subscribe ($status)"
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            parser.feed(value)
        }
    }

    private fun showFrame(jpeg: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        if (bmp == null) {
            Log.d("HnCam", "decode failed size=${jpeg.size}")
            return
        }
        _frame.value = bmp
        val now = SystemClock.elapsedRealtime()
        framesThisSecond++
        if (now - secondStart >= 1000) {
            _fps.value = framesThisSecond
            framesThisSecond = 0
            secondStart = now
            if (_status.value.startsWith("Receiving photo")) _status.value = "Connected"
        }
    }
}

/** Reassembles "XCAM"/"XSNP" packets from arbitrarily split chunks. */
class PacketParser(
    private val onPacket: (photo: Boolean, jpeg: ByteArray) -> Unit,
    private val onPhotoProgress: (percent: Int) -> Unit,
) {
    private companion object {
        const val MAX_PACKET = 2 * 1024 * 1024
    }

    private var buf = ByteArray(256 * 1024)
    private var len = 0
    private var lastProgress = -1

    fun reset() {
        len = 0
    }

    fun feed(chunk: ByteArray) {
        if (len + chunk.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + chunk.size))
        System.arraycopy(chunk, 0, buf, len, chunk.size)
        len += chunk.size
        parse()
    }

    private fun isMagic(i: Int): Boolean {
        if (buf[i] != 'X'.code.toByte()) return false
        val a = buf[i + 1].toInt().toChar()
        val b = buf[i + 2].toInt().toChar()
        val c = buf[i + 3].toInt().toChar()
        return (a == 'C' && b == 'A' && c == 'M') || (a == 'S' && b == 'N' && c == 'P')
    }

    private fun consume(n: Int) {
        System.arraycopy(buf, n, buf, 0, len - n)
        len -= n
    }

    private fun parse() {
        while (true) {
            var at = -1
            for (i in 0 until len - 3) if (isMagic(i)) { at = i; break }
            if (at < 0) {
                // Keep the last 3 bytes in case a header is split across chunks.
                if (len > 3) consume(len - 3)
                return
            }
            if (at > 0) consume(at)
            if (len < 8) return
            val photo = buf[1] == 'S'.code.toByte()
            val size = (buf[4].toInt() and 0xff) or ((buf[5].toInt() and 0xff) shl 8) or
                ((buf[6].toInt() and 0xff) shl 16) or ((buf[7].toInt() and 0xff) shl 24)
            if (size <= 0 || size > MAX_PACKET) { consume(4); continue }
            if (len < 8 + size) {
                if (photo) {
                    val p = 100 * (len - 8) / size
                    if (p / 10 != lastProgress / 10) { lastProgress = p; onPhotoProgress(p) }
                }
                return
            }
            val jpeg = buf.copyOfRange(8, 8 + size)
            consume(8 + size)
            lastProgress = -1
            if (jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte()) onPacket(photo, jpeg)
        }
    }
}
