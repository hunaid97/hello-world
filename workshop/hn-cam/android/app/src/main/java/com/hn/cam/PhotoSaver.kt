package com.hn.cam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RuntimeShader
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves a photo to the gallery under Pictures/HN_CAM, with [filters] applied at full
 * resolution. With no filters on, the board's JPEG is saved untouched.
 * Returns the file name, or null on failure.
 */
fun savePhoto(context: Context, jpeg: ByteArray, filters: Collection<Filter>, past: List<Bitmap>, time: Float): String? {
    val bytes = if (filters.isEmpty()) jpeg else {
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        val src = applyCpuFilters(filters, decoded)
        val inputs = FilterInputs(src.width.toFloat(), src.height.toFloat(), time, past)
        val effect = buildEffect(filters, inputs, HashMap<Filter, RuntimeShader>())
        val out = if (effect == null) src else renderEffect(src, effect)
        ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
    }

    val name = "HN_CAM_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HN_CAM")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    return try {
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        name
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        null
    }
}
