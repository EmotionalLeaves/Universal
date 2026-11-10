package com.emotionalleaves.universe.engine

import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Splits a big set of points (stars, galaxies) into spatially compact chunks so that each frame only
 * the chunks in view are drawn, and of those only the points bright enough to show.
 *
 * Points are put in [order] (chunk after chunk along a space-filling curve; inside a chunk brightest
 * first) and uploaded in that order. [select] then gives the ranges to draw: chunks outside the view
 * cone are skipped, and a chunk's faint tail is cut where it would be invisible from this distance.
 * The picture is exactly the same as drawing everything; it is just several times less work.
 */
class ChunkIndex(
    val count: Int,
    /** Position in the upload order -> original index. Dropped once uploaded ([release]). */
    var order: IntArray?,
    private val first: IntArray,
    private val size: IntArray,
    private val cx: FloatArray, private val cy: FloatArray, private val cz: FloatArray, private val cr: FloatArray,
    /** Per chunk, Q + 1 absolute magnitudes at even steps through its (sorted) points. */
    private val qmag: FloatArray,
) {
    val chunks get() = first.size
    /** Ranges from the last [select]: first, count pairs. */
    val ranges = IntArray(first.size * 2)
    var rangeCount = 0
        private set
    var selected = 0
        private set

    fun release() { order = null }

    /**
     * Works out what to draw. [off] = batch anchor minus camera (metres), [unit] = metres per position
     * unit, [magLimit] = the magnitude drawn at full brightness (points 6 magnitudes fainter vanish),
     * [share] = at most this fraction of each chunk (brightest first). [clip]: optional sphere
     * (x, y, z relative to the camera, radius; metres) outside which nothing is drawn.
     */
    fun select(off: Vec3d, unit: Double, orientation: Quatd, fovY: Double, aspect: Double, magLimit: Float, share: Float = 1f, clip: DoubleArray? = null): Int {
        val right = orientation.rotate(Vec3d(1.0, 0.0, 0.0))
        val up = orientation.rotate(Vec3d(0.0, 1.0, 0.0))
        val fwd = orientation.rotate(Vec3d(0.0, 0.0, -1.0))
        val ay = fovY / 2 * 1.06
        val ax = atan(tan(ay) * aspect)
        val sx = sin(ax); val cxn = cos(ax); val sy = sin(ay); val cyn = cos(ay)
        var n = 0
        var total = 0
        var lastEnd = -1
        for (c in first.indices) {
            val px = cx[c] * unit + off.x; val py = cy[c] * unit + off.y; val pz = cz[c] * unit + off.z
            val rr = cr[c] * unit
            val dist = sqrt(px * px + py * py + pz * pz)
            if (clip != null) {
                val dx = px - clip[0]; val dy = py - clip[1]; val dz = pz - clip[2]
                if (sqrt(dx * dx + dy * dy + dz * dz) > clip[3] + rr) continue
            }
            var take = size[c]
            if (dist > rr) {
                // View cone: four side planes in camera space.
                val vx = px * right.x + py * right.y + pz * right.z
                val vy = px * up.x + py * up.y + pz * up.z
                val vf = px * fwd.x + py * fwd.y + pz * fwd.z
                if (vf * sx - vx * cxn < -rr || vf * sx + vx * cxn < -rr || vf * sy - vy * cyn < -rr || vf * sy + vy * cyn < -rr) continue
                // Brightness: nothing in the chunk is nearer than dist - rr.
                val dm = 5 * log10((dist - rr) / Units.PC) - 5
                val cut = magLimit + 6.05 - dm
                val q = c * (Q + 1)
                if (qmag[q] > cut) continue
                if (qmag[q + Q] > cut) {
                    var k = 0
                    while (k < Q && qmag[q + k + 1] <= cut) k++
                    take = minOf(take, ceil((k + 1).toDouble() * size[c] / Q).toInt())
                }
            }
            var start = first[c]
            if (share < 1f) {
                // A thinned chunk shows its middle (typical) points, not its few brightest: like an even
                // sample, so the thinned view keeps the same balance of light.
                val want = maxOf(1, ceil(size[c] * share.toDouble()).toInt())
                if (want < take) { start += (take - want) / 2; take = want }
            }
            if (take <= 0) continue
            if (start == lastEnd && take == size[c] && n > 0) {
                ranges[n * 2 - 1] += take
            } else {
                ranges[n * 2] = start; ranges[n * 2 + 1] = take; n++
            }
            lastEnd = if (take == size[c]) first[c] + take else -1
            total += take
        }
        rangeCount = n
        selected = total
        return total
    }

    companion object {
        const val Q = 16

        /**
         * Builds the index for [count] points read through [x], [y], [z] (any unit) and [mag] (absolute
         * magnitude). Run it off the drawing thread for big sets: about a second per few million points.
         */
        fun build(count: Int, leaf: Int, x: (Int) -> Float, y: (Int) -> Float, z: (Int) -> Float, mag: (Int) -> Float): ChunkIndex {
            if (count == 0) return ChunkIndex(0, IntArray(0), IntArray(0), IntArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0))
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE
            var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
            for (i in 0 until count) {
                val a = x(i); val b = y(i); val c = z(i)
                if (a < x0) x0 = a; if (a > x1) x1 = a
                if (b < y0) y0 = b; if (b > y1) y1 = b
                if (c < z0) z0 = c; if (c > z1) z1 = c
            }
            val span = maxOf(x1 - x0, y1 - y0, z1 - z0, 1e-20f)
            val order = IntArray(count)
            if (count <= leaf) {
                for (i in 0 until count) order[i] = i
            } else {
                // Sort along a Z-order curve (13 bits per axis): neighbours on the curve are neighbours in space.
                val keys = LongArray(count)
                val k = 8191.0 / span
                for (i in 0 until count) {
                    val code = spread(((x(i) - x0) * k).toInt()) or (spread(((y(i) - y0) * k).toInt()) shl 1) or (spread(((z(i) - z0) * k).toInt()) shl 2)
                    keys[i] = (code shl 24) or i.toLong()
                }
                java.util.Arrays.sort(keys)
                for (i in 0 until count) order[i] = (keys[i] and 0xFFFFFF).toInt()
            }
            val nChunks = (count + leaf - 1) / leaf
            val first = IntArray(nChunks); val size = IntArray(nChunks)
            val cx = FloatArray(nChunks); val cy = FloatArray(nChunks); val cz = FloatArray(nChunks); val cr = FloatArray(nChunks)
            val qmag = FloatArray(nChunks * (Q + 1))
            val tmp = LongArray(leaf)
            for (c in 0 until nChunks) {
                val f = c * leaf
                val n = minOf(leaf, count - f)
                first[c] = f; size[c] = n
                var ax0 = Float.MAX_VALUE; var ay0 = Float.MAX_VALUE; var az0 = Float.MAX_VALUE
                var ax1 = -Float.MAX_VALUE; var ay1 = -Float.MAX_VALUE; var az1 = -Float.MAX_VALUE
                for (j in 0 until n) {
                    val i = order[f + j]
                    val a = x(i); val b = y(i); val d = z(i)
                    if (a < ax0) ax0 = a; if (a > ax1) ax1 = a
                    if (b < ay0) ay0 = b; if (b > ay1) ay1 = b
                    if (d < az0) az0 = d; if (d > az1) az1 = d
                    // Brightest first: magnitude (offset to stay positive) above the index.
                    val m = ((mag(i).coerceIn(-60f, 60f) + 64f) * 1000f).toLong()
                    tmp[j] = (m shl 32) or i.toLong()
                }
                java.util.Arrays.sort(tmp, 0, n)
                for (j in 0 until n) order[f + j] = (tmp[j] and 0xFFFFFFFFL).toInt()
                val mx = (ax0 + ax1) / 2; val my = (ay0 + ay1) / 2; val mz = (az0 + az1) / 2
                cx[c] = mx; cy[c] = my; cz[c] = mz
                val hx = (ax1 - ax0) / 2; val hy = (ay1 - ay0) / 2; val hz = (az1 - az0) / 2
                cr[c] = sqrt(hx * hx + hy * hy + hz * hz) * 1.001f
                for (q in 0..Q) qmag[c * (Q + 1) + q] = (tmp[minOf(n - 1, q * n / Q)] shr 32) / 1000f - 64f
            }
            return ChunkIndex(count, order, first, size, cx, cy, cz, cr, qmag)
        }

        /** Spreads the low 13 bits of [v] so two zero bits sit between each. */
        private fun spread(v: Int): Long {
            var a = (v.coerceIn(0, 8191)).toLong()
            a = (a or (a shl 16)) and 0x0000FF0000FFL
            a = (a or (a shl 8)) and 0x00F00F00F00FL
            a = (a or (a shl 4)) and 0x0C30C30C30C3L
            a = (a or (a shl 2)) and 0x249249249249L
            return a
        }
    }
}

/**
 * Where each cell of a streamed tile file lives (record count and byte offset by cell key), in three
 * plain arrays. A hash map of these took five times the memory for the 470,000 cells of the sky files.
 */
class CellIndex(keysIn: LongArray, countsIn: IntArray, offsetsIn: LongArray) {
    private val keys = keysIn.copyOf().also { java.util.Arrays.sort(it) }
    private val counts = IntArray(keys.size)
    private val offsets = LongArray(keys.size)
    val size get() = keys.size

    init {
        for (i in keysIn.indices) {
            val at = java.util.Arrays.binarySearch(keys, keysIn[i])
            counts[at] = countsIn[i]; offsets[at] = offsetsIn[i]
        }
    }

    fun containsKey(k: Long) = java.util.Arrays.binarySearch(keys, k) >= 0

    /** Record count and byte offset of cell [k], or null if the file has no such cell. */
    operator fun get(k: Long): LongArray? {
        val at = java.util.Arrays.binarySearch(keys, k)
        return if (at < 0) null else longArrayOf(counts[at].toLong(), offsets[at])
    }
}
