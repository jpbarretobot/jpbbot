package dev.studiocamera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** Palette sobre et professionnelle : fond sombre, accent or chaud. */
object Palette {
    const val BG = 0xFF0B0B0C.toInt()
    const val SURFACE = 0xFF17171A.toInt()
    const val SURFACE_2 = 0xFF24242A.toInt()
    const val TEXT = 0xFFEDEDED.toInt()
    const val MUTED = 0xFF9A9AA3.toInt()
    const val ACCENT = 0xFFF2C14E.toInt()
    const val OK = 0xFF5BD18A.toInt()
    const val WARN = 0xFFF2A54E.toInt()
    const val BAD = 0xFFE5655B.toInt()
    const val GENERATED = 0x66FF3DA5
}

fun Context.dp(value: Number): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).roundToInt()

fun Context.text(
    content: String,
    size: Float = 14f,
    color: Int = Palette.TEXT,
    bold: Boolean = false,
    gravity: Int = Gravity.START,
): TextView = TextView(this).apply {
    text = content
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    setTextColor(color)
    if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    this.gravity = gravity
}

fun pill(color: Int, radius: Float = 999f, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = radius
    setColor(color)
    if (stroke != 0) setStroke(2, stroke)
}

/** Bouton pilule ; [active] le met en évidence. */
fun Context.button(label: String, active: Boolean = false, onClick: () -> Unit): TextView = text(
    label,
    size = 13f,
    color = if (active) Palette.BG else Palette.TEXT,
    bold = active,
    gravity = Gravity.CENTER,
).apply {
    background = pill(if (active) Palette.ACCENT else Palette.SURFACE_2)
    setPadding(dp(14), dp(8), dp(14), dp(8))
    isClickable = true
    setOnClickListener { onClick() }
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
}

fun Context.column(block: LinearLayout.() -> Unit = {}): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    block()
}

fun Context.row(block: LinearLayout.() -> Unit = {}): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    block()
}

fun LinearLayout.add(view: View, width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f): View {
    addView(view, LinearLayout.LayoutParams(width, height, weight))
    return view
}

/**
 * Curseur à valeur réelle : échelle linéaire ou logarithmique, option « Auto » (valeur nulle).
 * [onChange] reçoit la valeur, ou null lorsque le mode automatique est choisi.
 */
class ParamSlider(
    context: Context,
    title: String,
    min: Float,
    max: Float,
    private val logScale: Boolean,
    private val format: (Float) -> String,
    private val allowAuto: Boolean,
    private val onChange: (Float?) -> Unit,
    private val onCommit: () -> Unit = {},
    private val defaultValue: Float? = null,
) : LinearLayout(context) {

    private val valueView = context.text("", size = 13f, color = Palette.ACCENT, bold = true)
    private val seek = SeekBar(context).apply { this.max = 1000 }
    private val autoBox: CheckBox? = if (allowAuto) CheckBox(context).apply {
        text = "Auto"
        setTextColor(Palette.TEXT)
        textSize = 12f
        isChecked = true
    } else null

    private var min = min
    private var max = max
    private var current: Float? = null
    private var auto = allowAuto
    private var suppress = false

    /** Met à jour la plage (ex. plage réelle de l'objectif) en conservant la valeur courante si possible. */
    fun setRange(newMin: Float, newMax: Float) {
        min = newMin
        max = newMax
        current?.let { current = it.coerceIn(min, max) }
        if (auto) refreshText() else setValue(current)
    }

    init {
        orientation = VERTICAL
        setPadding(context.dp(12), context.dp(6), context.dp(12), context.dp(6))
        val header = context.row {
            add(context.text(title, size = 12f, color = Palette.MUTED), width = 0, weight = 1f)
            add(valueView)
            defaultValue?.let { def ->
                add(context.text("↺", size = 16f, color = Palette.ACCENT, gravity = Gravity.CENTER).apply {
                    setPadding(context.dp(10), 0, context.dp(4), 0)
                    contentDescription = "Remettre la valeur par défaut"
                    setOnClickListener {
                        setValue(def)
                        onChange(def)
                        onCommit()
                    }
                })
            }
            autoBox?.let { box ->
                add(box, width = LayoutParams.WRAP_CONTENT)
                box.setOnCheckedChangeListener { _, checked ->
                    if (suppress) return@setOnCheckedChangeListener
                    auto = checked
                    seek.isEnabled = !checked
                    if (checked) {
                        onChange(null)
                    } else {
                        onChange(valueOf(seek.progress))
                    }
                    onCommit()
                    refreshText()
                }
            }
        }
        addView(header)
        addView(seek, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || suppress) return
                current = valueOf(progress)
                refreshText()
                onChange(current)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) = onCommit()
        })
        refreshText()
    }

    /** Positionne le curseur sans déclencher de callback. [value] null = automatique. */
    fun setValue(value: Float?) {
        suppress = true
        auto = value == null && allowAuto
        autoBox?.isChecked = auto
        if (value != null) {
            current = value.coerceIn(min, max)
            seek.progress = progressOf(current!!)
        }
        seek.isEnabled = !auto
        suppress = false
        refreshText()
    }

    private fun refreshText() {
        valueView.text = when {
            auto && allowAuto -> "Auto"
            current == null -> "—"
            else -> format(current!!)
        }
    }

    private fun valueOf(progress: Int): Float {
        val t = progress / 1000f
        return if (logScale && min > 0f) {
            exp(ln(min) + t * (ln(max) - ln(min)))
        } else {
            min + t * (max - min)
        }
    }

    private fun progressOf(value: Float): Int {
        val t = if (logScale && min > 0f) {
            (ln(value.coerceAtLeast(min)) - ln(min)) / (ln(max) - ln(min))
        } else {
            (value - min) / (max - min)
        }
        return (t.coerceIn(0f, 1f) * 1000).roundToInt()
    }
}

/** Grille des tiers, dessinée par-dessus l'aperçu. */
class GridOverlay(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 255, 255)
        strokeWidth = context.dp(0.8f).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..2) {
            canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, paint)
            canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, paint)
        }
    }
}

/** Cercle de mise au point affiché au point touché. */
class FocusRing(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.ACCENT
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, height) / 2f - paint.strokeWidth
        canvas.drawCircle(width / 2f, height / 2f, r, paint)
    }
}

/** Séparateur horizontal discret. */
fun Context.divider(): View = View(this).apply {
    setBackgroundColor(0x22FFFFFF)
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
}
