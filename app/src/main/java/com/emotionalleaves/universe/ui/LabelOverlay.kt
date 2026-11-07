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
        textSize = 13f * density
        setShadowLayer(3f * density, 0f, 0f, 0xFF000000.toInt())
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

    /** Call from any thread after updating [labels]. */
    fun refresh() = postInvalidateOnAnimation()

    override fun onDraw(canvas: Canvas) {
        val list = labels
        for (l in list) {
            val p = if (l.small) smallPaint else paint
            p.color = l.color
            p.typeface = if (l.selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val dx = l.offset + 6f * density
            canvas.drawText(l.text, l.x + dx, l.y - 4f * density, p)
            if (l.selected) {
                ring.color = 0xCCFFD27A.toInt()
                canvas.drawCircle(l.x, l.y, (l.offset + 4f * density).coerceAtLeast(10f * density), ring)
            }
        }
    }
}
