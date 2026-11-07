package com.emotionalleaves.universe.core

import kotlin.math.floor

/**
 * A position anywhere in the universe, precise to well under a millimetre everywhere.
 *
 * Space is cut into cubes of [CELL] metres (2^40 m, about 7 AU). The integer part says which cube,
 * the double part says where inside it. Differences between two positions are exact when they are
 * near each other, which is all the renderer needs (the floating origin: everything is drawn
 * relative to the camera).
 */
class UPos(
    val cx: Long, val cy: Long, val cz: Long,
    val x: Double, val y: Double, val z: Double,
) {
    operator fun plus(v: Vec3d): UPos {
        val (ncx, nx) = norm(cx, x + v.x)
        val (ncy, ny) = norm(cy, y + v.y)
        val (ncz, nz) = norm(cz, z + v.z)
        return UPos(ncx, ncy, ncz, nx, ny, nz)
    }

    operator fun minus(v: Vec3d): UPos = this + (-v)

    /** Vector from [o] to this position, in metres. */
    operator fun minus(o: UPos): Vec3d = Vec3d(
        (cx - o.cx).toDouble() * CELL + (x - o.x),
        (cy - o.cy).toDouble() * CELL + (y - o.y),
        (cz - o.cz).toDouble() * CELL + (z - o.z),
    )

    /** Position as a plain vector from the universe origin (loses precision far away; for display only). */
    fun toVec(): Vec3d = this - ZERO

    fun distanceTo(o: UPos) = (this - o).length()

    override fun equals(other: Any?): Boolean =
        other is UPos && cx == other.cx && cy == other.cy && cz == other.cz &&
            x == other.x && y == other.y && z == other.z

    override fun hashCode(): Int = (cx * 31 + cy * 17 + cz).hashCode() xor x.hashCode()

    override fun toString() = "UPos($cx,$cy,$cz | $x,$y,$z)"

    /** Compact text form for bookmarks. */
    fun encode(): String = "$cx,$cy,$cz,$x,$y,$z"

    companion object {
        const val CELL = 1099511627776.0 // 2^40 metres
        val ZERO = UPos(0, 0, 0, 0.0, 0.0, 0.0)

        fun of(v: Vec3d): UPos = ZERO + v

        fun decode(s: String): UPos? = try {
            val p = s.split(",")
            UPos(p[0].toLong(), p[1].toLong(), p[2].toLong(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble())
        } catch (_: Exception) {
            null
        }

        /** Linear interpolation between two positions. */
        fun lerp(a: UPos, b: UPos, t: Double): UPos = a + (b - a) * t

        private fun norm(c: Long, v: Double): Pair<Long, Double> {
            if (v >= 0.0 && v < CELL) return c to v
            val k = floor(v / CELL)
            return (c + k.toLong()) to (v - k * CELL)
        }
    }
}
