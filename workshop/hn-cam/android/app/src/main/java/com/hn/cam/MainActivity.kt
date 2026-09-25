package com.hn.cam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
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

    private val filterIndex = mutableStateOf(0)
    private val taking = mutableStateOf(false)
    private val lastSaved = mutableStateOf("")

    // Read on the Bluetooth thread when a photo arrives.
    @Volatile private var photoFilter: Filter = FILTERS[0]

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
            val name = savePhoto(applicationContext, jpeg, photoFilter)
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
        val filter = FILTERS[filterIndex.value]
        photoFilter = filter

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
                Text("HN_CAM", style = Mono.copy(fontSize = 22.sp))
                Spacer(Modifier.weight(1f))
                Text(if (connected && status == "Connected") "$fps fps" else status, style = Mono)
            }

            Preview(filter)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FILTERS.forEachIndexed { i, f ->
                    val selected = i == filterIndex.value
                    Box(
                        Modifier.weight(1f)
                            .border(1.dp, Color.White)
                            .background(if (selected) Color.White else Color.Black)
                            .clickable { filterIndex.value = i }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(f.name, style = Mono.copy(color = if (selected) Color.Black else Color.White))
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Text(lastSaved.value, style = Mono.copy(fontSize = 12.sp, fontWeight = FontWeight.Light),
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
    private fun Preview(filter: Filter) {
        val frame by link.frame.collectAsState()
        val paint = Paint()
        Box(
            Modifier.fillMaxWidth().aspectRatio(4f / 3f).border(1.dp, Color.White),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = frame
            if (bmp == null) {
                Text("Waiting for the camera", style = Mono.copy(fontWeight = FontWeight.Light))
            } else {
                Canvas(Modifier.fillMaxSize().padding(1.dp)) {
                    // Fit the frame in the box, then let the filter paint it.
                    val scale = minOf(size.width / bmp.width, size.height / bmp.height)
                    val w = bmp.width * scale
                    val h = bmp.height * scale
                    val dx = (size.width - w) / 2
                    val dy = (size.height - h) / 2
                    val image = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                        setLocalMatrix(Matrix().apply { setScale(scale, scale); postTranslate(dx, dy) })
                    }
                    paint.shader = filter.previewShader(image, w, h)
                    drawIntoCanvas { it.nativeCanvas.drawRect(dx, dy, dx + w, dy + h, paint) }
                }
            }
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
