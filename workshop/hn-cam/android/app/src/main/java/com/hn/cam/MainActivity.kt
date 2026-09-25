package com.hn.cam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

private val Lettera = FontFamily(
    Font(R.font.lettera_regular, FontWeight.Normal),
    Font(R.font.lettera_light, FontWeight.Light),
)
private val Mono = TextStyle(fontFamily = Lettera, color = Color.White, fontSize = 14.sp, letterSpacing = 0.5.sp)

class MainActivity : ComponentActivity() {
    private lateinit var link: HnCamLink

    private val active = mutableStateOf(setOf<Filter>())
    private val taking = mutableStateOf(false)
    private val lastSaved = mutableStateOf("")

    // Read on the Bluetooth thread when a photo arrives.
    @Volatile private var photoFilters: Set<Filter> = emptySet()

    // Compiled shaders for the live view (UI thread only; photos compile their own).
    private val previewShaders = HashMap<Filter, RuntimeShader>()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val startMs = SystemClock.elapsedRealtime()
    private fun clock() = (SystemClock.elapsedRealtime() - startMs) / 1000f

    private val permissions = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPermissions()) link.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        link = HnCamLink(applicationContext)
        link.onPhoto = { jpeg ->
            val name = savePhoto(applicationContext, jpeg, photoFilters, link.recentFrames(), clock())
            lastSaved.value = name?.let { "Saved Pictures/HN_CAM/$it" } ?: "Couldn't save the photo"
            taking.value = false
        }

        setContent { Screen() }
    }

    override fun onStart() {
        super.onStart()
        if (hasPermissions()) link.start() else askPermissions.launch(permissions)
    }

    override fun onStop() {
        link.stop()
        super.onStop()
    }

    private fun hasPermissions() = permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    @Composable
    private fun Screen() {
        val status by link.status.collectAsState()
        val connected by link.connected.collectAsState()
        val fps by link.fps.collectAsState()
        val filters = active.value
        photoFilters = filters

        // If a photo never arrives (link dropped mid-transfer), let the shutter work again.
        LaunchedEffect(taking.value) {
            if (taking.value) {
                delay(15_000)
                if (taking.value) {
                    taking.value = false
                    lastSaved.value = "The photo didn't arrive. Try again."
                }
            }
        }

        Column(
            Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("HN_CAM", style = Mono)
                Spacer(Modifier.weight(1f))
                Text(if (connected && status == "Connected") "$fps fps" else status, style = Mono)
            }

            Preview(filters)

            // Every filter toggles on and off; any combination stacks.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FILTERS.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { f ->
                            val on = f in filters
                            Box(
                                Modifier.weight(1f)
                                    .border(1.dp, Color.White)
                                    .background(if (on) Color.White else Color.Black)
                                    .clickable { active.value = if (on) filters - f else filters + f }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(f.name, style = Mono.copy(color = if (on) Color.Black else Color.White))
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Text(lastSaved.value, style = Mono.copy(fontWeight = FontWeight.Light),
                modifier = Modifier.align(Alignment.CenterHorizontally))

            Shutter(
                enabled = connected && !taking.value,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                if (link.requestPhoto()) {
                    taking.value = true
                    lastSaved.value = "Taking photo"
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    @Composable
    private fun Preview(filters: Set<Filter>) {
        val frame by link.frame.collectAsState()
        val photo by link.photo.collectAsState()
        Box(
            Modifier.fillMaxWidth().aspectRatio(4f / 3f).border(1.dp, Color.White),
            contentAlignment = Alignment.Center,
        ) {
            val transfer = photo
            val bmp = if (transfer != null) transfer.image else frame
            if (bmp == null && transfer == null) {
                Text("Waiting for the camera", style = Mono.copy(fontWeight = FontWeight.Light))
            }
            Box(Modifier.fillMaxSize().padding(1.dp)) {
                if (bmp != null) {
                    // The image, with the filter stack applied to the whole layer on the GPU.
                    Canvas(Modifier.fillMaxSize().graphicsLayer {
                        clip = true
                        val inputs = FilterInputs(size.width, size.height, clock(), link.recentFrames())
                        renderEffect = buildEffect(filters, inputs, previewShaders)?.asComposeRenderEffect()
                    }) {
                        drawIntoCanvas { it.nativeCanvas.drawBitmap(bmp, null, RectF(0f, 0f, size.width, size.height), bitmapPaint) }
                    }
                }
                // The mosaic of blocks still to come sits on top, unfiltered.
                val layout = transfer?.layout
                if (transfer != null && !transfer.done && layout != null) {
                    Canvas(Modifier.fillMaxSize()) {
                        val blocks = if (bmp == null) 0 else transfer.blocksDone
                        drawIntoCanvas { drawMosaic(it.nativeCanvas, layout, blocks, size.width, size.height) }
                    }
                }
            }
            if (transfer != null) TransferReadout(transfer, Modifier.align(Alignment.BottomStart))
        }
    }

    private val gridPaint = Paint().apply { color = 0x2EFFFFFF; strokeWidth = 1f }
    private val headPaint = Paint().apply { color = android.graphics.Color.WHITE }
    private val emptyPaint = Paint().apply { color = android.graphics.Color.BLACK }

    /**
     * Covers the blocks that haven't arrived: black, with a faint grid of empty cells, and
     * the block being written right now in white. [w] x [h] is the photo's size on screen.
     */
    private fun drawMosaic(c: android.graphics.Canvas, layout: JpegLayout, blocksDone: Int, w: Float, h: Float) {
        val sx = w / layout.width
        val sy = h / layout.height
        val cellW = layout.blockW * sx
        val cellH = layout.blockH * sy
        val row = blocksDone / layout.cols
        val col = blocksDone % layout.cols
        val rowTop = row * cellH
        val rowBottom = minOf(h, rowTop + cellH)

        // Missing area: the rest of the current block row, then everything below it.
        val missing = android.graphics.Path().apply {
            addRect(col * cellW, rowTop, w, rowBottom, android.graphics.Path.Direction.CW)
            addRect(0f, rowBottom, w, h, android.graphics.Path.Direction.CW)
        }
        c.drawPath(missing, emptyPaint)

        // Grid lines on real block edges; skip some if the blocks are tiny on screen.
        val stepX = maxOf(1, kotlin.math.ceil(10f / cellW).toInt())
        val stepY = maxOf(1, kotlin.math.ceil(10f / cellH).toInt())
        c.save()
        c.clipPath(missing)
        var x = 0
        while (x <= layout.cols) { c.drawLine(x * cellW, rowTop, x * cellW, h, gridPaint); x += stepX }
        var y = row
        while (y <= layout.rows) { c.drawLine(0f, y * cellH, w, y * cellH, gridPaint); y += stepY }
        c.restore()

        // The write head.
        if (blocksDone < layout.cols * layout.rows) {
            c.drawRect(col * cellW, rowTop, col * cellW + maxOf(cellW, 3f), rowTop + maxOf(cellH, 3f), headPaint)
        }
    }

    /** Percentage, packets and bytes of the photo on its way, over the viewfinder. */
    @Composable
    private fun TransferReadout(t: HnCamLink.PhotoTransfer, modifier: Modifier) {
        val fraction = if (t.total > 0) t.received.toFloat() / t.total else 0f
        Column(modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 1.dp).background(Color.Black).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(if (t.done) "SAVED" else "RECEIVING  ${(fraction * 100).toInt()}%", style = Mono)
                Text(
                    "${t.packets} PACKETS · ${t.received / 1024} / ${t.total / 1024} KB",
                    style = Mono.copy(fontWeight = FontWeight.Light),
                )
            }
            Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(Color.White))
        }
    }

    @Composable
    private fun Shutter(enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Box(
            modifier.size(76.dp).alpha(if (enabled) 1f else 0.35f).border(2.dp, Color.White)
                .clickable(enabled = enabled, onClick = onClick).padding(8.dp),
        ) {
            Box(Modifier.fillMaxSize().background(Color.White))
        }
    }
}
