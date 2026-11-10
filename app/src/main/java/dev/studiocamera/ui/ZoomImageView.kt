package dev.studiocamera.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import kotlin.math.min

/**
 * Image affichée à l'échelle de la vue (sans déformation) : pincer pour zoomer, faire glisser un doigt
 * quand elle est agrandie, double-tap pour revenir à l'échelle initiale.
 */
class ZoomImageView(context: Context) : ImageView(context) {

    private var zoom = 1f
    private var tx = 0f
    private var ty = 0f
    private val fitMatrix = Matrix()
    private val userMatrix = Matrix()

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (zoom * detector.scaleFactor).coerceIn(1f, MAX_ZOOM)
            val real = next / zoom
            zoom = next
            tx *= real
            ty *= real
            clampAndApply()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (zoom > 1f) {
                tx -= distanceX
                ty -= distanceY
                clampAndApply()
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoom = 1f
            tx = 0f
            ty = 0f
            applyMatrix()
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        applyMatrix()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyMatrix()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun clampAndApply() {
        val vw = (width - paddingLeft - paddingRight).toFloat()
        val vh = (height - paddingTop - paddingBottom).toFloat()
        val maxX = (zoom - 1f) * vw / 2f
        val maxY = (zoom - 1f) * vh / 2f
        tx = tx.coerceIn(-maxX, maxX)
        ty = ty.coerceIn(-maxY, maxY)
        applyMatrix()
    }

    private fun applyMatrix() {
        val d = drawable ?: return
        val vw = (width - paddingLeft - paddingRight).toFloat()
        val vh = (height - paddingTop - paddingBottom).toFloat()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (vw <= 0f || vh <= 0f || dw <= 0f || dh <= 0f) return
        val fit = min(vw / dw, vh / dh)
        val cx = paddingLeft + vw / 2f
        val cy = paddingTop + vh / 2f
        fitMatrix.reset()
        fitMatrix.postTranslate(-dw / 2f, -dh / 2f)
        fitMatrix.postScale(fit, fit)
        fitMatrix.postTranslate(cx, cy)
        userMatrix.reset()
        userMatrix.postScale(zoom, zoom, cx, cy)
        userMatrix.postTranslate(tx, ty)
        val m = Matrix(fitMatrix)
        m.postConcat(userMatrix)
        imageMatrix = m
    }

    companion object {
        private const val MAX_ZOOM = 5f
    }
}
