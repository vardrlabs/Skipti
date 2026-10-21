package com.vardrlabs.skipti

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * D-235 (Android audit H2): the one decoder for a picture that came from the cloud: a group's photo, your picture from
 * another phone, a member's preview. A member can write any JPEG under the size limit into a group's log, and a file of
 * 64,000 bytes can declare 7000 by 7000 pixels in its frame header: decoded whole it runs this phone out of memory (an
 * Error, uncaught, on every launch, because the record stays in the vault) or makes a bitmap too large to draw.
 *
 * So the header is read first ([BitmapFactory.Options.inJustDecodeBounds]: nothing is allocated), a picture past
 * core's bounds is refused, the rest is decoded at a sample size near what is drawn, and every failure (an Exception or
 * an OutOfMemoryError) is null: the caller draws its fallback (the group's letter, the person's initials).
 *
 * The bounds are core's own (`core/src/straumr/groups.rs`, `PICTURE_MAX_*` and `THUMB_MAX_*`, which the ingest's
 * `check_jpeg_hex` also applies); a JVM test reads them there.
 */
object SafeImage {
    data class Caps(val maxSide: Int, val maxPixels: Long)

    /** A group's photo or your own picture (core's `PICTURE_MAX_SIDE` / `PICTURE_MAX_PIXELS`). */
    val PHOTO = Caps(maxSide = 2048, maxPixels = 2048L * 1024)

    /** A member's preview (core's `THUMB_MAX_SIDE` / `THUMB_MAX_PIXELS`). */
    val PREVIEW = Caps(maxSide = 512, maxPixels = 512L * 512)

    /** What a group tile, an avatar and the full-screen viewer draw at most, in pixels on the shorter side. */
    const val TILE_TARGET = 768
    const val AVATAR_TARGET = 512
    const val VIEWER_TARGET = 2048

    /** The sample size that decodes a [width] by [height] picture at no less than [target] pixels on its shorter side
     *  (a power of two, as BitmapFactory rounds it), or null when the picture is refused: no size, a side past
     *  [Caps.maxSide], or more pixels than [Caps.maxPixels]. Pure: the JVM tests pin it. */
    fun sampleSize(width: Int, height: Int, caps: Caps, target: Int): Int? {
        if (width <= 0 || height <= 0) return null
        if (width > caps.maxSide || height > caps.maxSide) return null
        if (width.toLong() * height.toLong() > caps.maxPixels) return null
        var sample = 1
        while (minOf(width, height) / (sample * 2) >= target) sample *= 2
        return sample
    }

    /** [bytes] as a bitmap, or null: not a picture, past [caps], or the decoder failed in any way. */
    fun decode(bytes: ByteArray, caps: Caps, target: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = sampleSize(bounds.outWidth, bounds.outHeight, caps, target)
        if (sample == null) {
            null
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    /** A hex string's picture (the vault keeps photos as hex), or null as [decode]. */
    fun decodeHex(hex: String, caps: Caps, target: Int): Bitmap? {
        val bytes = try {
            HexCodec.decode(hex)
        } catch (_: Exception) {
            return null
        }
        return decode(bytes, caps, target)
    }
}
