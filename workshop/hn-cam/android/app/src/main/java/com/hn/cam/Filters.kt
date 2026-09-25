package com.hn.cam

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader

/**
 * A filter is usually an AGSL shader, run on the GPU as one RenderEffect pass. It reads what's
 * underneath from `uniform shader image` in pixel coordinates of an image [FilterInputs.width]
 * wide, and sets its own uniforms in [uniforms], relative to that size, so the live view and
 * the saved photo look the same.
 *
 * A few effects can't be done per pixel (sorting needs a whole column at once); those set
 * [cpu] instead and run on the bitmap before any GPU pass.
 *
 * Any set of filters can be on at once; they always stack in [stage] order.
 * To add one: write the shader, add a Filter to [FILTERS].
 */
class Filter(
    val name: String,
    val stage: Int,
    val agsl: String?,
    val uniforms: RuntimeShader.(FilterInputs) -> Unit = {},
    val cpu: ((Bitmap) -> Bitmap)? = null,
)

/** Runs the CPU filters among [active] over [src], scaled down to [maxWidth] first if given. */
fun applyCpuFilters(active: Collection<Filter>, src: Bitmap, maxWidth: Int = Int.MAX_VALUE): Bitmap {
    val cpu = active.filter { it.cpu != null }.sortedBy { it.stage }
    if (cpu.isEmpty()) return src
    var bmp = if (src.width > maxWidth) {
        Bitmap.createScaledBitmap(src, maxWidth, src.height * maxWidth / src.width, true)
    } else src
    for (f in cpu) bmp = f.cpu!!(bmp)
    return bmp
}

/** What a filter can read besides the image itself. */
class FilterInputs(
    val width: Float,
    val height: Float,
    /** Seconds, for animated filters. */
    val time: Float,
    /** Recent live frames, newest first (for PAST LATENCY). */
    val past: List<Bitmap>,
)

/**
 * Builds the stacked effect for [active] filters. [shaders] caches compiled shaders per
 * filter; use one cache per thread. Returns null when nothing is on.
 */
fun buildEffect(active: Collection<Filter>, inputs: FilterInputs, shaders: MutableMap<Filter, RuntimeShader>): RenderEffect? {
    var effect: RenderEffect? = null
    for (f in active.filter { it.agsl != null }.sortedBy { it.stage }) {
        val s = shaders.getOrPut(f) { RuntimeShader(f.agsl!!) }
        f.uniforms(s, inputs)
        val pass = RenderEffect.createRuntimeShaderEffect(s, "image")
        // createChainEffect(outer, inner): inner runs first.
        effect = if (effect == null) pass else RenderEffect.createChainEffect(pass, effect)
    }
    return effect
}

/** Runs [effect] over [src] offscreen on the GPU, at full resolution. */
fun renderEffect(src: Bitmap, effect: RenderEffect): Bitmap {
    val w = src.width
    val h = src.height
    val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
    val node = RenderNode("hn-cam-photo").apply {
        setPosition(0, 0, w, h)
        setRenderEffect(effect)
        beginRecording().drawBitmap(src, 0f, 0f, null)
        endRecording()
    }
    val renderer = HardwareRenderer().apply {
        setSurface(reader.surface)
        setContentRoot(node)
    }
    try {
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        reader.acquireNextImage().use { image ->
            val buffer = image.hardwareBuffer!!
            val out = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))!!
                .copy(Bitmap.Config.ARGB_8888, false)
            buffer.close()
            return out
        }
    } finally {
        renderer.destroy()
        node.discardDisplayList()
        reader.close()
    }
}

/** A past frame as a shader stretched over the image, for sampling at the same coordinates. */
private fun frameShader(frame: Bitmap, inputs: FilterInputs) =
    BitmapShader(frame, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        setLocalMatrix(Matrix().apply { setScale(inputs.width / frame.width, inputs.height / frame.height) })
    }

// Shared helpers, pasted into each shader.
private const val COMMON = """
uniform shader image;
uniform float2 size;
half3 at(float2 q) { return image.eval(clamp(q, float2(0.5), size - 0.5)).rgb; }
half luma(half3 c) { return dot(c, half3(0.299, 0.587, 0.114)); }
"""

private const val DUOTONE = COMMON + """
layout(color) uniform half4 dark;
layout(color) uniform half4 light;

half4 main(float2 p) {
    half l = smoothstep(0.08, 0.92, luma(at(p)));
    return half4(mix(dark.rgb, light.rgb, l), 1.0);
}
"""

// Flat posterized colour with black ink lines on edges (Sobel on brightness).
private const val CARTOON = COMMON + """
half4 main(float2 p) {
    float d = max(1.0, size.x / 400.0);
    half tl = luma(at(p + float2(-d, -d))), t = luma(at(p + float2(0, -d))), tr = luma(at(p + float2(d, -d)));
    half l  = luma(at(p + float2(-d, 0))),                                   r  = luma(at(p + float2(d, 0)));
    half bl = luma(at(p + float2(-d, d))),  b = luma(at(p + float2(0, d))),  br = luma(at(p + float2(d, d)));
    half gx = (tr + 2.0 * r + br) - (tl + 2.0 * l + bl);
    half gy = (bl + 2.0 * b + br) - (tl + 2.0 * t + tr);
    half ink = smoothstep(0.22, 0.45, sqrt(gx * gx + gy * gy));

    half3 c = floor(at(p) * 4.0 + 0.5) / 4.0;
    half g = luma(c);
    c = clamp(mix(half3(g), c, 1.5), 0.0, 1.0);
    return half4(mix(c, half3(0.0), ink), 1.0);
}
"""

// Thermal camera, full rainbow: black, deep blue, cyan, green, yellow, orange, red, magenta, white.
private const val HEAT = COMMON + """
half3 palette(half t) {
    half3 c = mix(half3(0.0), half3(0.05, 0.0, 0.6), smoothstep(0.0, 0.12, t));
    c = mix(c, half3(0.0, 0.85, 1.0), smoothstep(0.12, 0.3, t));
    c = mix(c, half3(0.1, 1.0, 0.2), smoothstep(0.3, 0.45, t));
    c = mix(c, half3(1.0, 1.0, 0.0), smoothstep(0.45, 0.6, t));
    c = mix(c, half3(1.0, 0.5, 0.0), smoothstep(0.6, 0.72, t));
    c = mix(c, half3(1.0, 0.0, 0.1), smoothstep(0.72, 0.84, t));
    c = mix(c, half3(1.0, 0.0, 0.9), smoothstep(0.84, 0.93, t));
    return mix(c, half3(1.0), smoothstep(0.93, 1.0, t));
}

half4 main(float2 p) {
    float d = size.x / 160.0;
    half t = (luma(at(p)) * 2.0 + luma(at(p + float2(d, 0))) + luma(at(p - float2(d, 0)))
            + luma(at(p + float2(0, d))) + luma(at(p - float2(0, d)))) / 6.0;
    // Stretch the contrast so a normal scene spans more of the palette.
    return half4(palette(smoothstep(0.1, 0.85, t)), 1.0);
}
"""

// Classic pixelation: square blocks, each one crisp colour taken from its centre.
private const val PIXELATE = COMMON + """
half4 main(float2 p) {
    float block = size.x / 32.0;
    return half4(at((floor(p / block) + 0.5) * block), 1.0);
}
"""

// Each colour channel runs on its own delay: red is now, green ~0.2 s ago, blue ~0.35 s ago.
// Only the change between frames is taken from the past, so a still scene stays sharp and
// anything moving leaves coloured trails.
private const val PAST_LATENCY = COMMON + """
uniform shader latest;
uniform shader pastG;
uniform shader pastB;

half4 main(float2 p) {
    half3 now = at(p);
    half3 ref = latest.eval(p).rgb;
    half g = now.g + (pastG.eval(p).g - ref.g);
    half b = now.b + (pastB.eval(p).b - ref.b);
    return half4(clamp(half3(now.r, g, b), 0.0, 1.0), 1.0);
}
"""

// Flowing two-layer warp, a slow swirl around a drifting centre, colour fringes along the
// warp, then a soft glow and pastel colour drifting across the frame.
private const val DREAMSCAPE = COMMON + """
uniform float time;

half4 main(float2 p) {
    float2 uv = p / size;
    float2 flow = float2(sin(uv.y * 5.0 + time * 0.7) + 0.5 * sin(uv.y * 13.0 - time * 1.3),
                         cos(uv.x * 4.0 + time * 0.5) + 0.5 * cos(uv.x * 11.0 + time * 1.1)) * size.x * 0.02;

    float2 centre = size * float2(0.5 + 0.25 * sin(time * 0.3), 0.5 + 0.2 * cos(time * 0.4));
    float2 d = p - centre;
    float fall = max(0.0, 1.0 - length(d) / (size.x * 0.5));
    float angle = 1.6 * fall * fall * sin(time * 0.45);
    float sn = sin(angle);
    float cs = cos(angle);
    float2 q = centre + float2(d.x * cs - d.y * sn, d.x * sn + d.y * cs) + flow;

    float2 split = flow * 0.4;
    half3 c = half3(at(q + split).r, at(q).g, at(q - split).b);

    half3 glow = half3(0.0);
    float r = size.x * 0.025;
    for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.785398;
        glow += at(q + r * float2(cos(a), sin(a)));
    }
    glow /= 8.0;
    half3 col = 1.0 - (1.0 - c) * (1.0 - glow * 0.75); // screen blend

    half3 tint = 0.5 + 0.5 * cos(6.28318 * (half3(0.0, 0.33, 0.67) + uv.x * 0.3 + uv.y * 0.2 + time * 0.05));
    col = mix(col, col * (0.55 + 0.6 * tint), 0.55);
    return half4(clamp(col, 0.0, 1.0), 1.0);
}
"""

// Squares of one flat colour each. Start big; wherever a square covers too much contrast,
// split it into four, up to four times. Flat areas stay as large squares, detail gets small ones.
private const val GEOMETRIC = COMMON + """
half3 average(float2 o, float s) {
    return (at(o + s * float2(0.25, 0.25)) + at(o + s * float2(0.75, 0.25))
          + at(o + s * float2(0.25, 0.75)) + at(o + s * float2(0.75, 0.75))) * 0.25;
}

half spread(float2 o, float s) {
    half a = luma(at(o + s * float2(0.2, 0.2)));
    half b = luma(at(o + s * float2(0.8, 0.2)));
    half c = luma(at(o + s * float2(0.2, 0.8)));
    half d = luma(at(o + s * float2(0.8, 0.8)));
    half e = luma(at(o + s * float2(0.5, 0.5)));
    return max(max(max(a, b), max(c, d)), e) - min(min(min(a, b), min(c, d)), e);
}

half4 main(float2 p) {
    float s = size.x / 6.0;
    float2 o = floor(p / s) * s;
    for (int i = 0; i < 4; i++) {
        if (spread(o, s) > 0.12) {
            s *= 0.5;
            o = floor(p / s) * s;
        }
    }
    half3 c = average(o, s);
    float2 f = (p - o) / s;
    float edge = min(min(f.x, 1.0 - f.x), min(f.y, 1.0 - f.y)) * s;
    half seam = 1.0 - smoothstep(0.0, max(1.0, size.x / 700.0), edge);
    return half4(mix(c, half3(0.0), seam * 0.85), 1.0);
}
"""

// A pixel grid where random spots swell: at each spot the grid is magnified, so its middle
// pixels grow big and the ones around the rim get squeezed. Spots pulse over time.
private const val SWELL = COMMON + """
uniform float time;

float hash(float2 c) { return fract(sin(dot(c, float2(127.1, 311.7))) * 43758.5453); }

half4 main(float2 p) {
    float cell = size.x / 5.0;
    float2 home = floor(p / cell);
    float2 w = p;      // where p lands on the undistorted grid
    float best = 1.0;  // distance to the nearest spot, as a fraction of its radius
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            float2 k = home + float2(float(i), float(j));
            float h = hash(k);
            if (h > 0.3) {
                float2 c = (k + 0.2 + 0.6 * float2(hash(k + 7.1), hash(k + 3.7))) * cell;
                float radius = cell * (0.55 + 0.35 * sin(time * (0.6 + h) + h * 6.2831));
                float2 d = p - c;
                float t = length(d) / radius;
                if (t < best) {
                    best = t;
                    // Exponent < 1 pulls the middle together (bigger pixels) and packs the rim.
                    w = c + d * pow(max(t, 0.0001), 0.9);
                }
            }
        }
    }
    float block = size.x / 48.0;
    return half4(at((floor(w / block) + 0.5) * block), 1.0);
}
"""

/**
 * Pixel sorting: in each column, runs of pixels brighter than a threshold are sorted by
 * brightness, dark to light, top to bottom. Dark pixels stay put and break the runs.
 */
private fun pixelSort(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    fun luma(c: Int) = (((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 + (c and 0xff) * 114) / 1000
    val threshold = 70
    val run = LongArray(h)
    for (x in 0 until w) {
        var y = 0
        while (y < h) {
            if (luma(px[y * w + x]) < threshold) { y++; continue }
            val start = y
            var n = 0
            while (y < h && luma(px[y * w + x]) >= threshold) {
                val c = px[y * w + x]
                // Brightness in the high bits sorts by it; the colour rides along in the low bits.
                run[n++] = (luma(c).toLong() shl 32) or (c.toLong() and 0xffffffffL)
                y++
            }
            java.util.Arrays.sort(run, 0, n)
            for (i in 0 until n) px[(start + i) * w + x] = run[i].toInt()
        }
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}

private fun RuntimeShader.size(i: FilterInputs) = setFloatUniform("size", i.width, i.height)

private fun RuntimeShader.timed(i: FilterInputs) {
    size(i)
    setFloatUniform("time", i.time)
}

/** In the order the buttons show them. [Filter.stage] decides the stacking order. */
val FILTERS = listOf(
    Filter("CARTOON", 6, CARTOON, { size(it) }),
    Filter("HEAT", 7, HEAT, { size(it) }),
    Filter("PIXELATE", 3, PIXELATE, { size(it) }),
    Filter("PAST LATENCY", 1, PAST_LATENCY, { inputs ->
        size(inputs)
        // Frames arrive at about 24 fps: 5 and 8 frames back are ~0.2 s and ~0.35 s.
        val past = inputs.past
        val latest = past.firstOrNull()
        if (latest == null) {
            // No history yet (a photo right after connecting): no trails.
            val blank = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            listOf("latest", "pastG", "pastB").forEach { setInputShader(it, frameShader(blank, inputs)) }
        } else {
            setInputShader("latest", frameShader(latest, inputs))
            setInputShader("pastG", frameShader(past.getOrElse(5) { past.last() }, inputs))
            setInputShader("pastB", frameShader(past.getOrElse(8) { past.last() }, inputs))
        }
    }),
    Filter("DREAMSCAPE", 5, DREAMSCAPE, { timed(it) }),
    Filter("GEOMETRIC", 4, GEOMETRIC, { size(it) }),
    Filter("SWELL", 2, SWELL, { timed(it) }),
    Filter("PIXEL SORT", 0, null, cpu = ::pixelSort),
    Filter("DUOTONE", 8, DUOTONE, { inputs ->
        size(inputs)
        setColorUniform("dark", Color.parseColor("#0B1F4B"))
        setColorUniform("light", Color.parseColor("#FF6B4A"))
    }),
)
