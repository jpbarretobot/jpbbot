package dev.studiocamera.io

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import dev.studiocamera.imaging.Frame
import dev.studiocamera.imaging.Plane
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Passerelles entre les images Android (Bitmap, JPEG, PNG) et le cœur de traitement pur Kotlin.
 */
object Bitmaps {

    fun toBitmap(frame: Frame): Bitmap =
        Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)

    fun fromBitmap(bitmap: Bitmap): Frame {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) pixels[i] = pixels[i] or (0xFF shl 24)
        return Frame(w, h, pixels)
    }

    /**
     * Décode un JPEG en limitant la taille (échantillonnage entier) et applique l'orientation EXIF,
     * de sorte que l'image de travail soit toujours « à l'endroit ».
     */
    fun decodeLimited(bytes: ByteArray, maxSide: Int): Frame {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val longest = max(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IllegalArgumentException("JPEG illisible")
        val rotated = applyExifOrientation(decoded, orientationOf(bytes))
        val frame = fromBitmap(rotated)
        if (rotated !== decoded) rotated.recycle()
        decoded.recycle()
        return frame.limitedTo(maxSide)
    }

    fun orientationOf(jpeg: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(jpeg))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    fun encodeJpeg(frame: Frame, quality: Int = 94): ByteArray {
        val bmp = toBitmap(frame)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** Masques et cartes de profondeur : PNG 8 bits sans perte (le niveau de gris encode la valeur). */
    fun encodePlanePng(plane: Plane): ByteArray {
        val pixels = IntArray(plane.size) { i ->
            val v = (plane.data[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val bmp = Bitmap.createBitmap(pixels, plane.width, plane.height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    fun decodePlanePng(bytes: ByteArray): Plane {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IllegalArgumentException("PNG illisible")
        val frame = fromBitmap(bmp)
        bmp.recycle()
        return frame.channel(0)
    }
}
