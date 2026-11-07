package com.emotionalleaves.universe.core

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Deterministic random numbers. Generated data must be seeded from cell IDs, never the clock
 * (rule 1 in docs/PLAN.md), so the same place always looks the same.
 */
class Rng(seed: Long) {
    private var state = seed

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** Uniform in [0, 1). */
    fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))

    fun range(a: Double, b: Double) = a + (b - a) * nextDouble()

    fun nextInt(n: Int): Int = ((nextLong() ushr 1) % n).toInt()

    fun chance(p: Double) = nextDouble() < p

    fun gaussian(): Double {
        val u1 = nextDouble().coerceAtLeast(1e-300)
        val u2 = nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
    }

    /** Poisson-distributed count with mean [mean] (Gaussian approximation for large means). */
    fun poisson(mean: Double): Int {
        if (mean <= 0) return 0
        if (mean > 40) return (mean + sqrt(mean) * gaussian()).toInt().coerceAtLeast(0)
        val l = exp(-mean)
        var k = 0
        var p = 1.0
        do {
            k++
            p *= nextDouble()
        } while (p > l)
        return k - 1
    }

    /** Random unit vector. */
    fun unitVector(): Vec3d {
        val z = range(-1.0, 1.0)
        val t = range(0.0, 2 * Math.PI)
        val r = sqrt(1 - z * z)
        return Vec3d(r * cos(t), r * kotlin.math.sin(t), z)
    }

    companion object {
        /** Mixes several numbers into one well-spread seed. */
        fun hash(vararg values: Long): Long {
            var h = 0x2545F4914F6CDD1DL
            for (v in values) {
                h = h xor v
                h *= -0x4b47d5b1ad5c8c4dL
                h = h xor (h ushr 29)
            }
            return Rng(h).nextLong()
        }

        fun hashString(s: String): Long {
            var h = 1125899906842597L
            for (c in s) h = 31 * h + c.code
            return hash(h)
        }
    }
}
