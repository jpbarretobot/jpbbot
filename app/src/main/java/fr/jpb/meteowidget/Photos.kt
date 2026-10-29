package fr.jpb.meteowidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/** Photos personnelles choisies pour remplacer une illustration. */
object Photos {

    private const val MAX_W = 1200
    private const val MAX_H = 700

    private fun file(ctx: Context, b: Backdrop) = File(ctx.filesDir, "photo_${b.name.lowercase()}.jpg")

    fun has(ctx: Context, b: Backdrop) = file(ctx, b).exists()

    fun load(ctx: Context, b: Backdrop): Bitmap? =
        file(ctx, b).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    fun remove(ctx: Context, b: Backdrop) {
        file(ctx, b).delete()
    }

    /** Copie l'image choisie en la réduisant (les widgets ont une mémoire limitée). */
    fun importFrom(ctx: Context, b: Backdrop, uri: Uri): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return false
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_W && bounds.outHeight / (sample * 2) >= MAX_H) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return false
        val scale = minOf(1f, maxOf(MAX_W.toFloat() / src.width, MAX_H.toFloat() / src.height))
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        } else {
            src
        }
        file(ctx, b).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return true
    }
}
