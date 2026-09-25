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

    /** A photo on its way from the board, decoded as far as it has arrived. */
    class PhotoTransfer(
        val image: Bitmap?,
        val received: Int,
        val total: Int,
        val packets: Int,
        val done: Boolean,
        /** Photo size and JPEG block (MCU) size from its header, once that has arrived. */
        val layout: JpegLayout? = null,
        /** How many blocks, in reading order, have been decoded so far. */
        val blocksDone: Int = 0,
    )

    private val _photo = MutableStateFlow<PhotoTransfer?>(null)
    val photo: StateFlow<PhotoTransfer?> = _photo

    // The last half second of live frames, newest first, for filters that look back in time.
    private val history = ArrayDeque<Bitmap>()
    fun recentFrames(): List<Bitmap> = synchronized(history) { history.toList() }

    /** Called on the Bluetooth thread with each photo's JPEG bytes. */
    var onPhoto: ((ByteArray) -> Unit)? = null

    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val thread = HandlerThread("hn-cam-ble").apply { start() }
    private val handler = Handler(thread.looper)
    // Partial photo decodes run here so they never hold up incoming packets.
    private val decodeThread = HandlerThread("hn-cam-decode").apply { start() }
    private val decodeHandler = Handler(decodeThread.looper)
    @Volatile private var decodeBusy = false
    private var photoId = 0
    private var gatt: BluetoothGatt? = null
    private var ctrl: BluetoothGattCharacteristic? = null
    private var running = false
    private var scanning = false

    private val parser = PacketParser(
        onPacket = { photo, jpeg -> if (photo) photoDone(jpeg) else showFrame(jpeg) },
        onPhotoPartial = ::photoPartial,
    )
    private var photoPackets = 0
    private var lastPartialDecode = 0
    private val clearPhoto = Runnable { _photo.value = null }
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
                if (_photo.value?.done == false) _photo.value = null
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
            if (_photo.value?.done == false) photoPackets++
            parser.feed(value)
        }
    }

    // ---- Photo transfer, shown as it arrives ----

    private fun photoPartial(data: ByteArray, offset: Int, received: Int, total: Int) {
        val current = _photo.value
        if (current == null || current.done) {
            // First bytes of a new photo.
            handler.removeCallbacks(clearPhoto)
            photoId++
            photoPackets = 1
            lastPartialDecode = 0
            _photo.value = PhotoTransfer(null, received, total, photoPackets, false)
            return
        }
        val layout = current.layout ?: JpegLayout.parse(data, offset, received)
        _photo.value = PhotoTransfer(current.image, received, total, photoPackets, false, layout, current.blocksDone)
        if (layout == null || received - lastPartialDecode < 2048 || decodeBusy) return
        lastPartialDecode = received

        // Cap the truncated JPEG with an end-of-image marker. The decoder then renders every
        // block that has arrived and fills the rest with flat grey, instead of stopping at the
        // last complete row: that's what lets it build up block by block.
        val snapshot = data.copyOfRange(offset, offset + received + 2)
        snapshot[received] = 0xFF.toByte()
        snapshot[received + 1] = 0xD9.toByte()
        val id = photoId
        decodeBusy = true
        decodeHandler.post {
            val bmp = BitmapFactory.decodeByteArray(snapshot, 0, snapshot.size)
            val done = bmp?.let { blocksDecoded(it, layout) } ?: 0
            handler.post {
                val now = _photo.value
                if (bmp != null && id == photoId && now != null && !now.done) {
                    _photo.value = PhotoTransfer(bmp, now.received, now.total, now.packets, false, layout, done)
                }
                decodeBusy = false
            }
        }
    }

    private fun photoDone(jpeg: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        _photo.value = PhotoTransfer(bmp, jpeg.size, jpeg.size, photoPackets, true)
        onPhoto?.invoke(jpeg)
        // Keep the finished photo on screen for a moment, then go back to live.
        handler.postDelayed(clearPhoto, 1500)
    }

    private var pixels = IntArray(0)

    /**
     * Counts the blocks of a partly decoded JPEG that have arrived. Blocks past the end of the
     * data decode with all-zero coefficients, which is exactly mid grey (128, 128, 128). Walk
     * back from the last block while blocks are that grey; the first one that isn't is the
     * last block received. (Scanning from the end means real grey areas earlier in the photo
     * aren't mistaken for missing blocks.)
     */
    private fun blocksDecoded(bmp: Bitmap, layout: JpegLayout): Int {
        val w = bmp.width
        val h = bmp.height
        if (pixels.size < w * h) pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        fun grey(x: Int, y: Int): Boolean {
            val c = pixels[minOf(y, h - 1) * w + minOf(x, w - 1)]
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff
            return r in 125..131 && g in 125..131 && b in 125..131
        }
        val bw = layout.blockW
        val bh = layout.blockH
        var i = layout.cols * layout.rows - 1
        while (i >= 0) {
            val x = (i % layout.cols) * bw
            val y = (i / layout.cols) * bh
            val missing = grey(x + 1, y + 1) && grey(x + bw - 2, y + 1) && grey(x + bw / 2, y + bh / 2) &&
                grey(x + 1, y + bh - 2) && grey(x + bw - 2, y + bh - 2)
            if (!missing) break
            i--
        }
        return i + 1
    }

    private fun showFrame(jpeg: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        if (bmp == null) {
            Log.d("HnCam", "decode failed size=${jpeg.size}")
            return
        }
        synchronized(history) {
            history.addFirst(bmp)
            while (history.size > 12) history.removeLast()
        }
        _frame.value = bmp
        // A live frame while a photo was half done means the board gave up on the photo.
        if (_photo.value?.done == false) _photo.value = null
        val now = SystemClock.elapsedRealtime()
        framesThisSecond++
        if (now - secondStart >= 1000) {
            _fps.value = framesThisSecond
            framesThisSecond = 0
            secondStart = now
        }
    }
}

/** Reassembles "XCAM"/"XSNP" packets from arbitrarily split chunks. */
class PacketParser(
    private val onPacket: (photo: Boolean, jpeg: ByteArray) -> Unit,
    /** While a photo is arriving: its bytes so far are data[offset until offset + received]. */
    private val onPhotoPartial: (data: ByteArray, offset: Int, received: Int, total: Int) -> Unit,
) {
    private companion object {
        const val MAX_PACKET = 2 * 1024 * 1024
    }

    private var buf = ByteArray(256 * 1024)
    private var len = 0

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
                if (photo) onPhotoPartial(buf, 8, len - 8, size)
                return
            }
            val jpeg = buf.copyOfRange(8, 8 + size)
            consume(8 + size)
            if (jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte()) onPacket(photo, jpeg)
        }
    }
}

/** Size of a JPEG and of its blocks (MCUs), read from the SOF header. */
class JpegLayout(val width: Int, val height: Int, val blockW: Int, val blockH: Int) {
    val cols = (width + blockW - 1) / blockW
    val rows = (height + blockH - 1) / blockH

    companion object {
        fun parse(d: ByteArray, offset: Int, len: Int): JpegLayout? {
            fun u8(i: Int) = d[offset + i].toInt() and 0xff
            var i = 2 // after SOI
            while (i + 4 <= len) {
                if (u8(i) != 0xFF) return null
                val marker = u8(i + 1)
                val segLen = (u8(i + 2) shl 8) or u8(i + 3)
                if (marker in 0xC0..0xC2) {
                    if (i + 10 > len) return null
                    val height = (u8(i + 5) shl 8) or u8(i + 6)
                    val width = (u8(i + 7) shl 8) or u8(i + 8)
                    val comps = u8(i + 9)
                    if (i + 10 + comps * 3 > len) return null
                    var hMax = 1
                    var vMax = 1
                    for (c in 0 until comps) {
                        val sampling = u8(i + 11 + c * 3)
                        hMax = maxOf(hMax, sampling shr 4)
                        vMax = maxOf(vMax, sampling and 0x0f)
                    }
                    return JpegLayout(width, height, 8 * hMax, 8 * vMax)
                }
                i += 2 + segLen
            }
            return null
        }
    }
}
