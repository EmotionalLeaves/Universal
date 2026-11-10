package com.emotionalleaves.universe.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

/** One on-screen label, in pixels. */
class Label(
    val x: Float,
    val y: Float,
    val text: String,
    val color: Int,
    val selected: Boolean = false,
    val offset: Float = 0f,
    val small: Boolean = false,
    /** Off-screen marker: draw an arrow at the screen edge pointing at [x], [y] direction. */
    val edge: Boolean = false,
)

/**
 * Draws object names over the 3D view. The renderer replaces [labels] every frame from the GL
 * thread; drawing a few dozen strings with Canvas is far cheaper than recomposing Compose.
 */
@SuppressLint("ViewConstructor")
class LabelOverlay(context: Context) : View(context) {
    @Volatile var labels: List<Label> = emptyList()
    private val density = context.resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12.5f * density
        setShadowLayer(2.5f * density, 0f, 0f, 0xCC000000.toInt())
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        setShadowLayer(3f * density, 0f, 0f, 0xFF000000.toInt())
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    init {
        setWillNotDraw(false)
    }

    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEEFFD27A.toInt() }

    /** Arrow at the edge of the screen pointing toward an off-screen selection; x/y = direction. */
    private fun drawEdgeArrow(canvas: Canvas, l: Label) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h / 2
        val len = kotlin.math.hypot(l.x, l.y).coerceAtLeast(1e-3f)
        val dx = l.x / len; val dy = l.y / len
        val margin = 36f * density
        val sx = if (dx != 0f) (cx - margin) / kotlin.math.abs(dx) else Float.MAX_VALUE
        val sy = if (dy != 0f) (cy - margin) / kotlin.math.abs(dy) else Float.MAX_VALUE
        val s = minOf(sx, sy)
        val px = cx + dx * s; val py = cy + dy * s
        canvas.drawCircle(px, py, 7f * density, arrow)
        canvas.drawLine(px, py, px - dx * 18f * density, py - dy * 18f * density, ring.apply { color = 0xEEFFD27A.toInt() })
        paint.color = 0xFFFFD27A.toInt()
        val tx = if (dx > 0) px - paint.measureText(l.text) - 14f * density else px + 14f * density
        canvas.drawText(l.text, tx, py + 5f * density, paint)
    }

    @Volatile private var tapX = 0f
    @Volatile private var tapY = 0f
    @Volatile private var tapTime = 0L
    @Volatile private var tapHit = false

    /** Shows a short ripple where a tap landed: gold when it picked something, grey when nothing was there. */
    fun tapped(x: Float, y: Float, hit: Boolean) {
        tapX = x; tapY = y; tapHit = hit; tapTime = android.os.SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    /** Call from any thread after updating [labels]. */
    fun refresh() = postInvalidateOnAnimation()

    override fun onDraw(canvas: Canvas) {
        val age = (android.os.SystemClock.uptimeMillis() - tapTime) / 450f
        if (age in 0f..1f) {
            val a = ((1f - age) * (if (tapHit) 200 else 130)).toInt()
            ring.color = (a shl 24) or (if (tapHit) 0xFFD27A else 0xC8D0E0)
            canvas.drawCircle(tapX, tapY, (10f + 26f * age) * density, ring)
            postInvalidateOnAnimation()
        }
        val list = labels
        for (l in list) {
            if (l.edge) {
                drawEdgeArrow(canvas, l)
                continue
            }
            val p = if (l.small) smallPaint else paint
            // Softer, slightly see-through names so the sky stays the main thing (easier on the eyes);
            // the selection stays fully bright.
            p.color = if (l.selected) l.color else (l.color and 0x00FFFFFF) or (((l.color ushr 24) * 0.72f).toInt() shl 24)
            p.typeface = if (l.selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val dx = l.offset + 6f * density
            canvas.drawText(l.text, l.x + dx, l.y - 4f * density, p)
            // Big on screen already (a planet filling the view): a ring would just float in its middle.
            if (l.selected && l.offset < 160f) {
                ring.color = 0xCCFFD27A.toInt()
                canvas.drawCircle(l.x, l.y, (l.offset + 4f * density).coerceAtLeast(10f * density), ring)
            }
        }
    }
}
