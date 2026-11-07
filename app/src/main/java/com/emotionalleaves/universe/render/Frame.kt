package com.emotionalleaves.universe.render

import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Vec3d
import kotlin.math.log2
import kotlin.math.tan

/** Everything a layer needs to draw one frame. Positions are relative to [camPos]. */
class Frame(
    val camPos: UPos,
    val orientation: Quatd,
    val viewProj: FloatArray,
    val width: Int,
    val height: Int,
    val fovY: Double,
    val jd: Double,
    val time: Double,
    val focusDistance: Double,
) {
    /** 2 / log2(far + 1), used by the log-depth shader code. */
    val logF: Float = (2.0 / log2(FAR + 1.0)).toFloat()

    /** Camera-relative vector to [p]. */
    fun rel(p: UPos): Vec3d = p - camPos

    /** Size in pixels of something with [radius] metres at [distance] metres. */
    fun pixelSize(radius: Double, distance: Double): Double {
        if (distance <= 0) return Double.MAX_VALUE
        return radius / (distance * tan(fovY / 2)) * height / 2
    }

    /** Projects a camera-relative point to screen pixels (x right, y down), or null if behind. */
    fun project(rel: Vec3d): Pair<Float, Float>? {
        val c = orientation.conjugate().rotate(rel) // camera space
        if (c.z >= 0) return null
        val f = 1.0 / tan(fovY / 2)
        val aspect = width.toDouble() / height
        val nx = (c.x * f / aspect) / -c.z
        val ny = (c.y * f) / -c.z
        return ((nx + 1) * 0.5 * width).toFloat() to ((1 - ny) * 0.5 * height).toFloat()
    }

    /** World direction (from camera) through a screen pixel. */
    fun rayThrough(px: Float, py: Float): Vec3d {
        val f = tan(fovY / 2)
        val aspect = width.toDouble() / height
        val nx = (px / width.toDouble()) * 2 - 1
        val ny = 1 - (py / height.toDouble()) * 2
        val c = Vec3d(nx * f * aspect, ny * f, -1.0)
        return orientation.rotate(c).normalized()
    }

    companion object {
        const val FAR = 1.0e30
    }
}
