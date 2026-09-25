package com.hn.cam

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RuntimeShader
import android.graphics.Shader

/**
 * A filter is an AGSL shader run on the GPU. It reads the camera image from
 * `uniform shader image` and gets its size-dependent uniforms from [uniforms],
 * so the live view and the saved photo look the same at any resolution.
 *
 * To add a filter: write the shader, add a Filter to [FILTERS].
 */
class Filter(
    val name: String,
    private val agsl: String?,
    private val uniforms: RuntimeShader.(width: Float, height: Float) -> Unit = { _, _ -> },
) {
    /** Shader for the live view, reused every frame on the UI thread. */
    private val preview: RuntimeShader? = agsl?.let(::RuntimeShader)

    /** The shader to paint [image] with, for a draw area of [width] x [height]. */
    fun previewShader(image: Shader, width: Float, height: Float): Shader {
        val s = preview ?: return image
        s.setInputShader("image", image)
        s.uniforms(width, height)
        return s
    }

    /** Runs the filter over a full photo on the GPU. Null means "no filter": keep the original JPEG. */
    fun render(src: Bitmap): Bitmap? {
        if (agsl == null) return null
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val s = RuntimeShader(agsl) // separate instance: the live view keeps using its own
        s.setInputShader("image", BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        s.uniforms(w, h)
        val picture = Picture()
        picture.beginRecording(src.width, src.height).drawRect(0f, 0f, w, h, Paint().apply { shader = s })
        picture.endRecording()
        // createBitmap(Picture) renders with the GPU into a hardware bitmap; copy it back to encode.
        return Bitmap.createBitmap(picture).copy(Bitmap.Config.ARGB_8888, false)
    }
}

private const val DUOTONE = """
uniform shader image;
layout(color) uniform half4 dark;
layout(color) uniform half4 light;

half4 main(float2 p) {
    half3 c = image.eval(p).rgb;
    half l = dot(c, half3(0.2126, 0.7152, 0.0722));
    // A little contrast so the two tones separate cleanly.
    l = smoothstep(0.08, 0.92, l);
    return half4(mix(dark.rgb, light.rgb, l), 1.0);
}
"""

private const val PIXEL = """
uniform shader image;
uniform float block;

half4 main(float2 p) {
    // Average four samples inside the block so it doesn't flicker as much as a single sample.
    float2 origin = floor(p / block) * block;
    half4 sum = image.eval(origin + block * float2(0.25, 0.25))
              + image.eval(origin + block * float2(0.75, 0.25))
              + image.eval(origin + block * float2(0.25, 0.75))
              + image.eval(origin + block * float2(0.75, 0.75));
    return sum * 0.25;
}
"""

/** Blocks across the image width for PIXEL. */
private const val PIXEL_BLOCKS = 40f

val FILTERS = listOf(
    Filter("NONE", null),
    Filter("DUOTONE", DUOTONE, { _, _ ->
        setColorUniform("dark", Color.parseColor("#0B1F4B"))
        setColorUniform("light", Color.parseColor("#FF6B4A"))
    }),
    Filter("PIXEL", PIXEL, { width, _ ->
        setFloatUniform("block", width / PIXEL_BLOCKS)
    }),
)
