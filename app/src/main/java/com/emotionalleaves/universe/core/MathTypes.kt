package com.emotionalleaves.universe.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Double-precision 3D vector. All world math happens in doubles (see docs/PLAN.md). */
data class Vec3d(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3d) = Vec3d(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3d) = Vec3d(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3d(x * s, y * s, z * s)
    operator fun div(s: Double) = Vec3d(x / s, y / s, z / s)
    operator fun unaryMinus() = Vec3d(-x, -y, -z)
    fun dot(o: Vec3d) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3d) = Vec3d(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun lengthSq() = x * x + y * y + z * z
    fun length() = sqrt(lengthSq())
    fun normalized(): Vec3d {
        val l = length()
        return if (l > 0.0) Vec3d(x / l, y / l, z / l) else Vec3d(0.0, 0.0, 1.0)
    }
    fun isFinite() = x.isFinite() && y.isFinite() && z.isFinite()

    companion object {
        val ZERO = Vec3d(0.0, 0.0, 0.0)
        val X = Vec3d(1.0, 0.0, 0.0)
        val Y = Vec3d(0.0, 1.0, 0.0)
        val Z = Vec3d(0.0, 0.0, 1.0)

        /** Unit vector from right ascension / declination in degrees (equatorial frame, +Z = north pole). */
        fun fromRaDec(raDeg: Double, decDeg: Double): Vec3d {
            val ra = Math.toRadians(raDeg)
            val dec = Math.toRadians(decDeg)
            return Vec3d(cos(dec) * cos(ra), cos(dec) * sin(ra), sin(dec))
        }

        fun lerp(a: Vec3d, b: Vec3d, t: Double) = Vec3d(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)

        /** Any unit vector perpendicular to [v]. */
        fun perpendicular(v: Vec3d): Vec3d {
            val a = if (abs(v.x) < 0.9) X else Y
            return v.cross(a).normalized()
        }
    }
}

/** Unit quaternion for orientation (double precision). */
data class Quatd(val w: Double, val x: Double, val y: Double, val z: Double) {
    operator fun times(q: Quatd) = Quatd(
        w * q.w - x * q.x - y * q.y - z * q.z,
        w * q.x + x * q.w + y * q.z - z * q.y,
        w * q.y - x * q.z + y * q.w + z * q.x,
        w * q.z + x * q.y - y * q.x + z * q.w,
    )

    fun conjugate() = Quatd(w, -x, -y, -z)

    fun normalized(): Quatd {
        val l = sqrt(w * w + x * x + y * y + z * z)
        return if (l > 0) Quatd(w / l, x / l, y / l, z / l) else IDENTITY
    }

    fun rotate(v: Vec3d): Vec3d {
        // v' = q * v * q^-1, expanded
        val tx = 2.0 * (y * v.z - z * v.y)
        val ty = 2.0 * (z * v.x - x * v.z)
        val tz = 2.0 * (x * v.y - y * v.x)
        return Vec3d(
            v.x + w * tx + (y * tz - z * ty),
            v.y + w * ty + (z * tx - x * tz),
            v.z + w * tz + (x * ty - y * tx),
        )
    }

    companion object {
        val IDENTITY = Quatd(1.0, 0.0, 0.0, 0.0)

        fun axisAngle(axis: Vec3d, angle: Double): Quatd {
            val a = axis.normalized()
            val s = sin(angle / 2)
            return Quatd(cos(angle / 2), a.x * s, a.y * s, a.z * s)
        }

        /** Rotation taking the frame (right, up, back) into world, built from forward/up. Camera looks along -Z local. */
        fun lookRotation(forward: Vec3d, upHint: Vec3d): Quatd {
            val f = forward.normalized()
            var r = f.cross(upHint)
            if (r.lengthSq() < 1e-20) r = Vec3d.perpendicular(f)
            r = r.normalized()
            val u = r.cross(f)
            val b = -f
            return fromBasis(r, u, b)
        }

        /** Quaternion from orthonormal basis columns (x, y, z axes of the local frame in world space). */
        fun fromBasis(xa: Vec3d, ya: Vec3d, za: Vec3d): Quatd {
            val m00 = xa.x; val m01 = ya.x; val m02 = za.x
            val m10 = xa.y; val m11 = ya.y; val m12 = za.y
            val m20 = xa.z; val m21 = ya.z; val m22 = za.z
            val tr = m00 + m11 + m22
            return if (tr > 0) {
                val s = sqrt(tr + 1.0) * 2
                Quatd(0.25 * s, (m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s)
            } else if (m00 > m11 && m00 > m22) {
                val s = sqrt(1.0 + m00 - m11 - m22) * 2
                Quatd((m21 - m12) / s, 0.25 * s, (m01 + m10) / s, (m02 + m20) / s)
            } else if (m11 > m22) {
                val s = sqrt(1.0 + m11 - m00 - m22) * 2
                Quatd((m02 - m20) / s, (m01 + m10) / s, 0.25 * s, (m12 + m21) / s)
            } else {
                val s = sqrt(1.0 + m22 - m00 - m11) * 2
                Quatd((m10 - m01) / s, (m02 + m20) / s, (m12 + m21) / s, 0.25 * s)
            }.normalized()
        }

        fun slerp(a: Quatd, b0: Quatd, t: Double): Quatd {
            var b = b0
            var d = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z
            if (d < 0) { b = Quatd(-b.w, -b.x, -b.y, -b.z); d = -d }
            if (d > 0.9995) {
                return Quatd(
                    a.w + (b.w - a.w) * t, a.x + (b.x - a.x) * t,
                    a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t,
                ).normalized()
            }
            val th = acos(d.coerceIn(-1.0, 1.0))
            val s = sin(th)
            val wa = sin((1 - t) * th) / s
            val wb = sin(t * th) / s
            return Quatd(a.w * wa + b.w * wb, a.x * wa + b.x * wb, a.y * wa + b.y * wb, a.z * wa + b.z * wb)
        }
    }
}

/** Column-major 4x4 float matrices for OpenGL. */
object Mat4 {
    fun identity() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    /** Perspective projection with an infinite far plane. Depth is overridden by the log-depth shader code. */
    fun perspectiveInfinite(fovYRad: Double, aspect: Double, near: Double): FloatArray {
        val f = 1.0 / tan(fovYRad / 2)
        val m = FloatArray(16)
        m[0] = (f / aspect).toFloat()
        m[5] = f.toFloat()
        m[10] = -1f
        m[11] = -1f
        m[14] = (-2.0 * near).toFloat()
        return m
    }

    /** View rotation (no translation: the camera is always at the origin). */
    fun viewRotation(orientation: Quatd): FloatArray {
        val inv = orientation.conjugate()
        val xa = inv.rotate(Vec3d.X)
        val ya = inv.rotate(Vec3d.Y)
        val za = inv.rotate(Vec3d.Z)
        val m = identity()
        m[0] = xa.x.toFloat(); m[1] = xa.y.toFloat(); m[2] = xa.z.toFloat()
        m[4] = ya.x.toFloat(); m[5] = ya.y.toFloat(); m[6] = ya.z.toFloat()
        m[8] = za.x.toFloat(); m[9] = za.y.toFloat(); m[10] = za.z.toFloat()
        return m
    }

    /** Model matrix from rotation, uniform scale and translation (all relative to the camera). */
    fun model(rotation: Quatd, scale: Double, translation: Vec3d): FloatArray {
        val xa = rotation.rotate(Vec3d.X) * scale
        val ya = rotation.rotate(Vec3d.Y) * scale
        val za = rotation.rotate(Vec3d.Z) * scale
        val m = FloatArray(16)
        m[0] = xa.x.toFloat(); m[1] = xa.y.toFloat(); m[2] = xa.z.toFloat()
        m[4] = ya.x.toFloat(); m[5] = ya.y.toFloat(); m[6] = ya.z.toFloat()
        m[8] = za.x.toFloat(); m[9] = za.y.toFloat(); m[10] = za.z.toFloat()
        m[12] = translation.x.toFloat(); m[13] = translation.y.toFloat(); m[14] = translation.z.toFloat()
        m[15] = 1f
        return m
    }

    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (c in 0 until 4) for (row in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + row] * b[c * 4 + k]
            r[c * 4 + row] = s
        }
        return r
    }

    /** Rotation-only 3x3 from a quaternion (column-major), for normals. */
    fun rotation3(q: Quatd): FloatArray {
        val xa = q.rotate(Vec3d.X)
        val ya = q.rotate(Vec3d.Y)
        val za = q.rotate(Vec3d.Z)
        return floatArrayOf(
            xa.x.toFloat(), xa.y.toFloat(), xa.z.toFloat(),
            ya.x.toFloat(), ya.y.toFloat(), ya.z.toFloat(),
            za.x.toFloat(), za.y.toFloat(), za.z.toFloat(),
        )
    }
}

fun clamp(v: Double, lo: Double, hi: Double) = max(lo, min(hi, v))
fun smoothstep(e0: Double, e1: Double, x: Double): Double {
    val t = clamp((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)
}
fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

/** Right ascension (deg) and declination (deg) of a direction in the equatorial frame. */
fun raDecOf(v: Vec3d): Pair<Double, Double> {
    val n = v.normalized()
    var ra = Math.toDegrees(atan2(n.y, n.x))
    if (ra < 0) ra += 360.0
    val dec = Math.toDegrees(asin(n.z.coerceIn(-1.0, 1.0)))
    return ra to dec
}
