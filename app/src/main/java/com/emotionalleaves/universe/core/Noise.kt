package com.emotionalleaves.universe.core

import kotlin.math.abs
import kotlin.math.floor

/** Seeded 3D gradient noise and fractal sums, used for terrain, nebulae and the cosmic web. */
class Noise3(seed: Long) {
    private val perm = IntArray(512)
    private val grad = DoubleArray(256 * 3)

    init {
        val r = Rng(seed)
        val p = IntArray(256) { it }
        for (i in 255 downTo 1) {
            val j = r.nextInt(i + 1)
            val t = p[i]; p[i] = p[j]; p[j] = t
        }
        for (i in 0 until 512) perm[i] = p[i and 255]
        for (i in 0 until 256) {
            val v = r.unitVector()
            grad[i * 3] = v.x; grad[i * 3 + 1] = v.y; grad[i * 3 + 2] = v.z
        }
    }

    private fun fade(t: Double) = t * t * t * (t * (t * 6 - 15) + 10)

    private fun g(ix: Int, iy: Int, iz: Int, fx: Double, fy: Double, fz: Double): Double {
        val h = perm[perm[perm[ix and 255] + (iy and 255)] + (iz and 255)] * 3
        return grad[h] * fx + grad[h + 1] * fy + grad[h + 2] * fz
    }

    /** Gradient noise in roughly [-1, 1]. */
    fun noise(x: Double, y: Double, z: Double): Double {
        val x0 = floor(x); val y0 = floor(y); val z0 = floor(z)
        val ix = x0.toInt(); val iy = y0.toInt(); val iz = z0.toInt()
        val fx = x - x0; val fy = y - y0; val fz = z - z0
        val u = fade(fx); val v = fade(fy); val w = fade(fz)
        val n000 = g(ix, iy, iz, fx, fy, fz)
        val n100 = g(ix + 1, iy, iz, fx - 1, fy, fz)
        val n010 = g(ix, iy + 1, iz, fx, fy - 1, fz)
        val n110 = g(ix + 1, iy + 1, iz, fx - 1, fy - 1, fz)
        val n001 = g(ix, iy, iz + 1, fx, fy, fz - 1)
        val n101 = g(ix + 1, iy, iz + 1, fx - 1, fy, fz - 1)
        val n011 = g(ix, iy + 1, iz + 1, fx, fy - 1, fz - 1)
        val n111 = g(ix + 1, iy + 1, iz + 1, fx - 1, fy - 1, fz - 1)
        val nx00 = n000 + u * (n100 - n000)
        val nx10 = n010 + u * (n110 - n010)
        val nx01 = n001 + u * (n101 - n001)
        val nx11 = n011 + u * (n111 - n011)
        val nxy0 = nx00 + v * (nx10 - nx00)
        val nxy1 = nx01 + v * (nx11 - nx01)
        return (nxy0 + w * (nxy1 - nxy0)) * 1.6
    }

    fun fbm(x: Double, y: Double, z: Double, octaves: Int, lacunarity: Double = 2.0, gain: Double = 0.5): Double {
        var sum = 0.0
        var amp = 1.0
        var f = 1.0
        var norm = 0.0
        for (i in 0 until octaves) {
            sum += amp * noise(x * f + i * 17.13, y * f - i * 9.71, z * f + i * 3.37)
            norm += amp
            amp *= gain
            f *= lacunarity
        }
        return sum / norm
    }

    /** Ridged fractal noise in [0, 1]: sharp crests, good for mountains and cosmic filaments. */
    fun ridged(x: Double, y: Double, z: Double, octaves: Int): Double {
        var sum = 0.0
        var amp = 0.5
        var f = 1.0
        var prev = 1.0
        for (i in 0 until octaves) {
            var n = 1.0 - abs(noise(x * f, y * f, z * f))
            n *= n
            sum += n * amp * prev
            prev = n
            amp *= 0.5
            f *= 2.0
        }
        return sum.coerceIn(0.0, 1.0)
    }
}
