package com.emotionalleaves.universe.engine

import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import com.emotionalleaves.universe.core.clamp
import com.emotionalleaves.universe.core.smoothstep
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Orbit camera that works at every scale. You always orbit a focus point (usually an object).
 * Pinch changes the distance multiplicatively, so zoom speed is proportional to how far you are
 * from what you are looking at: centimetres near a rock, billions of light years near the edge.
 *
 * All methods must be called while holding the lock on this object.
 */
class CameraController {
    /** Point being orbited. Follows [target] if one is set. */
    var focus: UPos = UPos.ZERO
        private set
    /** Object being orbited, or null for empty space. */
    var target: Body? = null
        private set
    /** Distance from the focus, metres. */
    var distance: Double = 3.0e7
        private set
    /** Camera orientation; the camera looks along its local -Z. */
    var orientation: Quatd = Quatd.lookRotation(Vec3d(-1.0, 0.0, -0.3), Vec3d.Z)
        private set

    private var yawVel = 0.0
    private var pitchVel = 0.0
    private var zoomVel = 0.0
    private var flight: Flight? = null

    /** Extra minimum distance from the target's centre (terrain height etc.). */
    var surfaceExtra: Double = 0.0

    val isFlying get() = flight != null

    /** Camera position. */
    fun position(): UPos = focus + orientation.rotate(Vec3d.Z) * distance

    fun forward(): Vec3d = orientation.rotate(Vec3d(0.0, 0.0, -1.0))
    fun up(): Vec3d = orientation.rotate(Vec3d.Y)
    fun right(): Vec3d = orientation.rotate(Vec3d.X)

    fun minDistance(): Double {
        val t = target ?: return 0.05
        return max(t.radius + surfaceExtra + 0.8, 0.05)
    }

    fun maxDistance() = Units.UNIVERSE_RADIUS * 3.0

    /** Drag with one finger: orbit around the focus. Pixels -> radians. */
    fun rotate(dxPx: Float, dyPx: Float, viewHeightPx: Int) {
        cancelFlight()
        val k = 3.0 / max(viewHeightPx, 1)
        applyRotation(-dxPx * k, -dyPx * k)
        yawVel = -dxPx * k * 30
        pitchVel = -dyPx * k * 30
    }

    private fun applyRotation(yaw: Double, pitch: Double) {
        val q = Quatd.axisAngle(Vec3d.Y, yaw) * Quatd.axisAngle(Vec3d.X, pitch)
        orientation = (orientation * q).normalized()
    }

    /** Pinch: [factor] > 1 means fingers moved apart (zoom in). */
    fun zoom(factor: Float) {
        cancelFlight()
        if (factor <= 0f) return
        val f = factor.toDouble().pow(ZOOM_POWER)
        setDistance(distance / f)
        zoomVel = ln(f) * 20
    }

    /** Sets the distance directly (used by the zoom slider). */
    fun setDistance(d: Double) {
        distance = clamp(d, minDistance(), maxDistance())
    }

    /** Two-finger drag: slide the focus point sideways. Leaves the current target. */
    fun pan(dxPx: Float, dyPx: Float, viewHeightPx: Int) {
        cancelFlight()
        val worldPerPx = distance * 1.0 / max(viewHeightPx, 1)
        val move = right() * (-dxPx * worldPerPx) + up() * (dyPx * worldPerPx)
        if (target != null) {
            val keep = position()
            target = null
            surfaceExtra = 0.0
            focus = keep + forward() * distance
        }
        focus = focus + move
    }

    /** Twist with two fingers: roll around the view axis. */
    fun roll(angle: Double) {
        cancelFlight()
        orientation = (orientation * Quatd.axisAngle(Vec3d.Z, angle)).normalized()
    }

    /** Instantly orbit [body] at [dist] metres. */
    fun jumpTo(body: Body?, pos: UPos, dist: Double) {
        flight = null
        target = body
        surfaceExtra = 0.0
        focus = pos
        distance = clamp(dist, minDistance(), maxDistance())
    }

    /** Smooth flight to [body]. The camera rises out, travels and comes back down. */
    fun flyTo(body: Body?, destination: UPos, endDistance: Double, now: Double) {
        val startPos = focus
        val sep = (destination - startPos).length()
        val d0 = distance
        val d1 = endDistance
        val peak = max(max(d0, d1), sep * 0.8)
        val logSpan = ln(peak / min(d0, d1)).coerceAtLeast(1.0)
        val duration = clamp(1.4 + logSpan * 0.09, 1.6, 5.5)
        flight = Flight(startPos, d0, body, destination, d1, peak, now, duration)
        yawVel = 0.0; pitchVel = 0.0; zoomVel = 0.0
    }

    fun cancelFlight() {
        val f = flight ?: return
        // Stop where we are, keep looking at the destination.
        flight = null
        target = null
        surfaceExtra = 0.0
        focus = position() + forward() * distance
        if (f.body == null) return
    }

    /**
     * Per-frame update. [targetPosition] returns the current position of a body (bodies move with time).
     * [dt] seconds since last frame, [now] seconds clock.
     */
    fun update(dt: Double, now: Double, targetPosition: (Body) -> UPos) {
        val f = flight
        if (f != null) {
            val t = clamp((now - f.start) / f.duration, 0.0, 1.0)
            val dest = f.body?.let(targetPosition) ?: f.destination
            // Travel happens mostly while high up.
            val s = smoothstep(0.12, 0.88, t)
            focus = UPos.lerp(f.from, dest, s)
            val l0 = ln(f.d0); val l1 = ln(f.d1); val lp = ln(f.peak)
            val base = l0 + (l1 - l0) * smoothstep(0.0, 1.0, t)
            val bump = (lp - max(l0, l1)).coerceAtLeast(0.0) * sin(Math.PI * t)
            distance = exp(base + bump)
            if (t >= 1.0) {
                flight = null
                target = f.body
                focus = dest
                distance = clamp(f.d1, minDistance(), maxDistance())
            }
            return
        }
        target?.let { focus = targetPosition(it) }
        // Inertia
        if (abs(yawVel) > 1e-4 || abs(pitchVel) > 1e-4) {
            applyRotation(yawVel * dt, pitchVel * dt)
            val decay = exp(-dt * 5.0)
            yawVel *= decay; pitchVel *= decay
        }
        if (abs(zoomVel) > 1e-4) {
            setDistance(distance / exp(zoomVel * dt))
            zoomVel *= exp(-dt * 6.0)
        }
        if (distance < minDistance()) distance = minDistance()
    }

    /** Stops coasting (called when a finger touches down). */
    fun stopInertia() {
        yawVel = 0.0; pitchVel = 0.0; zoomVel = 0.0
    }

    /** Points the camera so that [worldDir] is straight ahead, keeping the focus fixed. */
    fun lookFrom(dirFromFocusToCamera: Vec3d, upHint: Vec3d) {
        orientation = Quatd.lookRotation(-dirFromFocusToCamera, upHint)
    }

    class Snapshot(val focus: UPos, val target: Body?, val distance: Double, val orientation: Quatd)

    fun snapshot() = Snapshot(focus, target, distance, orientation)

    fun restore(s: Snapshot) {
        focus = s.focus; target = s.target; distance = s.distance; orientation = s.orientation
        yawVel = 0.0; pitchVel = 0.0; zoomVel = 0.0
    }

    private class Flight(
        val from: UPos, val d0: Double, val body: Body?, val destination: UPos, val d1: Double,
        val peak: Double, val start: Double, val duration: Double,
    )

    companion object {
        /** Each pinch is amplified so you can cross the universe in a handful of gestures. */
        const val ZOOM_POWER = 2.2
    }
}
