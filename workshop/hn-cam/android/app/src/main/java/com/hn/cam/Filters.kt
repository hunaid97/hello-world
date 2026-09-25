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
 * A filter is an AGSL shader, run on the GPU as one RenderEffect pass. It reads what's
 * underneath from `uniform shader image` in pixel coordinates of an image [FilterInputs.width]
 * wide, and sets its own uniforms in [uniforms], relative to that size, so the live view and
 * the saved photo look the same.
 *
 * Any set of filters can be on at once; they always stack in [stage] order.
 * To add one: write the shader, add a Filter to [FILTERS].
 */
class Filter(
    val name: String,
    val stage: Int,
    val agsl: String,
    val uniforms: RuntimeShader.(FilterInputs) -> Unit,
)

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
    for (f in active.sortedBy { it.stage }) {
        val s = shaders.getOrPut(f) { RuntimeShader(f.agsl) }
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

// Thermal camera: soft brightness mapped onto black, violet, red, orange, yellow, white.
private const val HEAT = COMMON + """
half3 palette(half t) {
    half3 c = mix(half3(0.0), half3(0.25, 0.0, 0.55), smoothstep(0.0, 0.2, t));
    c = mix(c, half3(0.85, 0.0, 0.55), smoothstep(0.2, 0.4, t));
    c = mix(c, half3(1.0, 0.25, 0.0), smoothstep(0.4, 0.6, t));
    c = mix(c, half3(1.0, 0.75, 0.0), smoothstep(0.6, 0.8, t));
    return mix(c, half3(1.0, 1.0, 0.85), smoothstep(0.8, 1.0, t));
}

half4 main(float2 p) {
    float d = size.x / 160.0;
    half t = (luma(at(p)) * 2.0 + luma(at(p + float2(d, 0))) + luma(at(p - float2(d, 0)))
            + luma(at(p + float2(0, d))) + luma(at(p - float2(0, d)))) / 6.0;
    return half4(palette(t), 1.0);
}
"""

// Square blocks, each the true average of a 4x4 grid of samples inside it.
private const val PIXEL_AVG = COMMON + """
half4 main(float2 p) {
    float block = size.x / 40.0;
    float2 origin = floor(p / block) * block;
    half3 sum = half3(0.0);
    for (int y = 0; y < 4; y++) {
        for (int x = 0; x < 4; x++) {
            sum += at(origin + block * (float2(float(x), float(y)) + 0.5) / 4.0);
        }
    }
    return half4(sum / 16.0, 1.0);
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

// A slow liquid warp, a soft glow and pastel colour drifting across the frame.
private const val DREAMSCAPE = COMMON + """
uniform float time;

half4 main(float2 p) {
    float2 uv = p / size;
    float2 warp = float2(sin(uv.y * 6.0 + time * 0.8), cos(uv.x * 5.0 + time * 0.6)) * size.x * 0.01;
    float2 q = p + warp;
    half3 c = at(q);

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

// A lattice of triangles, each filled with the colour at its centre, with faint seams.
private const val GEOMETRIC = COMMON + """
half4 main(float2 p) {
    float s = size.x / 24.0;
    float2 cell = floor(p / s);
    float2 f = fract(p / s);
    bool flip = mod(cell.x + cell.y, 2.0) > 0.5;
    float2 centre;
    float diagonal;
    if (flip) {
        centre = (f.x + f.y < 1.0) ? float2(1.0 / 3.0) : float2(2.0 / 3.0);
        diagonal = abs(f.x + f.y - 1.0) * 0.7071;
    } else {
        centre = (f.x > f.y) ? float2(2.0 / 3.0, 1.0 / 3.0) : float2(1.0 / 3.0, 2.0 / 3.0);
        diagonal = abs(f.x - f.y) * 0.7071;
    }
    half3 c = at((cell + centre) * s);
    float edge = min(min(min(f.x, 1.0 - f.x), min(f.y, 1.0 - f.y)), diagonal);
    half seam = 1.0 - smoothstep(0.0, 0.035, edge);
    return half4(mix(c, c * 0.7, seam), 1.0);
}
"""

private fun RuntimeShader.size(i: FilterInputs) = setFloatUniform("size", i.width, i.height)

/** In the order the buttons show them. [Filter.stage] decides the stacking order. */
val FILTERS = listOf(
    Filter("CARTOON", 4, CARTOON) { size(it) },
    Filter("HEAT", 5, HEAT) { size(it) },
    Filter("PIXEL AVG", 1, PIXEL_AVG) { size(it) },
    Filter("PAST LATENCY", 0, PAST_LATENCY) { inputs ->
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
    },
    Filter("DREAMSCAPE", 3, DREAMSCAPE) { inputs ->
        size(inputs)
        setFloatUniform("time", inputs.time)
    },
    Filter("GEOMETRIC", 2, GEOMETRIC) { size(it) },
    Filter("DUOTONE", 6, DUOTONE) { inputs ->
        size(inputs)
        setColorUniform("dark", Color.parseColor("#0B1F4B"))
        setColorUniform("light", Color.parseColor("#FF6B4A"))
    },
)
