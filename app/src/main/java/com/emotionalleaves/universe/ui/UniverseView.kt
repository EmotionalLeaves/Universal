package com.emotionalleaves.universe.ui

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import com.emotionalleaves.universe.engine.Engine
import com.emotionalleaves.universe.render.UniverseRenderer
import kotlin.math.atan2
import kotlin.math.hypot

/** The OpenGL surface plus touch controls: one finger orbits, pinch zooms, two fingers pan/twist. */
@SuppressLint("ViewConstructor")
class UniverseView(context: Context, private val engine: Engine) : GLSurfaceView(context) {
    val renderer = UniverseRenderer(context, engine)

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private var lastX = 0f
    private var lastY = 0f
    private var lastDist = 0f
    private var lastAngle = 0f
    private var lastCx = 0f
    private var lastCy = 0f
    private var panAccum = 0f
    private var zoomAccum = 0f
    private var panning = false
    private var twoFinger = false
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var moved = 0f
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cam = engine.camera
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y
                downX = e.x; downY = e.y
                downTime = e.eventTime
                moved = 0f
                twoFinger = false
                synchronized(cam) { cam.stopInertia() }
                engine.onUserInteraction()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                twoFinger = true
                startTwo(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    if (!twoFinger) { twoFinger = true; startTwo(e) }
                    val d = dist(e)
                    val cx = (e.getX(0) + e.getX(1)) / 2
                    val cy = (e.getY(0) + e.getY(1)) / 2
                    val ang = angle(e)
                    synchronized(cam) {
                        if (lastDist > 10f && d > 10f) {
                            if (kotlin.math.abs(d / lastDist - 1f) > 0.002f) engine.retargetIfAdrift()
                            cam.zoom(d / lastDist)
                        }
                        val dx = cx - lastCx
                        val dy = cy - lastCy
                        panAccum += hypot(dx, dy)
                        zoomAccum += kotlin.math.abs(d - lastDist)
                        // A pinch always drifts a little; only treat it as a two-finger slide when the
                        // fingers clearly move together more than they spread, so pinching never knocks
                        // you off the object you are looking at (or the one you are flying to).
                        if (!panning && panAccum > PAN_THRESHOLD_PX * (if (cam.isFlying) 3 else 1) && panAccum > zoomAccum * 1.5f) panning = true
                        if (panning || (cam.target == null && !cam.isFlying)) cam.pan(dx, dy, height)
                        var da = ang - lastAngle
                        if (da > Math.PI) da -= (2 * Math.PI).toFloat()
                        if (da < -Math.PI) da += (2 * Math.PI).toFloat()
                        if (kotlin.math.abs(da) < 0.3f) cam.roll(-da.toDouble())
                    }
                    lastDist = d; lastCx = cx; lastCy = cy; lastAngle = ang
                    moved += 100f
                } else if (!twoFinger) {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    moved += hypot(dx, dy)
                    if (moved > TAP_SLOP_PX) {
                        synchronized(cam) { cam.rotate(dx, dy, height) }
                    }
                    lastX = e.x; lastY = e.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Continue with one finger without a jump.
                val remaining = if (e.actionIndex == 0) 1 else 0
                if (remaining < e.pointerCount) {
                    lastX = e.getX(remaining); lastY = e.getY(remaining)
                }
                // Back to one finger: let it orbit again (it can no longer count as a tap).
                if (e.pointerCount == 2) { twoFinger = false; moved = TAP_SLOP_PX * 10 }
            }
            MotionEvent.ACTION_UP -> {
                val quick = e.eventTime - downTime < 300
                if (!twoFinger && quick && moved < TAP_SLOP_PX) {
                    val isDouble = e.eventTime - lastTapTime < 350 && hypot(e.x - lastTapX, e.y - lastTapY) < 60f
                    if (isDouble) {
                        engine.onDoubleTap(e.x, e.y)
                        lastTapTime = 0L
                    } else {
                        engine.onTap(e.x, e.y)
                        lastTapTime = e.eventTime
                        lastTapX = e.x; lastTapY = e.y
                    }
                }
                twoFinger = false
            }
            MotionEvent.ACTION_CANCEL -> twoFinger = false
        }
        return true
    }

    private fun startTwo(e: MotionEvent) {
        lastDist = dist(e)
        lastCx = (e.getX(0) + e.getX(1)) / 2
        lastCy = (e.getY(0) + e.getY(1)) / 2
        lastAngle = angle(e)
        panAccum = 0f
        zoomAccum = 0f
        panning = false
    }

    private fun dist(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    private fun angle(e: MotionEvent) = atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0))

    companion object {
        private const val TAP_SLOP_PX = 18f
        private const val PAN_THRESHOLD_PX = 70f
    }
}
