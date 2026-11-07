package com.emotionalleaves.universe.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

object Units {
    const val KM = 1000.0
    const val AU = 1.495978707e11
    const val LY = 9.4607304725808e15
    const val PC = 3.0856775814913673e16
    const val KPC = PC * 1e3
    const val MPC = PC * 1e6
    const val MLY = LY * 1e6
    const val GLY = LY * 1e9
    const val R_SUN = 6.957e8
    const val R_EARTH = 6.371e6
    const val R_JUPITER = 6.9911e7

    /** Comoving radius of the observable universe (to the CMB), about 45.7 billion light years. */
    const val UNIVERSE_RADIUS = 45.7 * GLY

    /** Human-readable distance that switches units with scale: m, km, AU, ly, Mly, Gly. */
    fun formatDistance(m: Double): String {
        val a = abs(m)
        return when {
            a < 1000.0 -> fmt(m, "m")
            a < 0.01 * AU -> fmt(m / KM, "km")
            a < 0.05 * LY -> fmt(m / AU, "AU")
            a < 1e6 * LY -> fmt(m / LY, "ly")
            a < 1e9 * LY -> fmt(m / MLY, "Mly")
            else -> fmt(m / GLY, "Gly")
        }
    }

    private fun fmt(v: Double, unit: String): String {
        val a = abs(v)
        val s = when {
            a >= 100_000 -> String.format(Locale.US, "%,.0f", v)
            a >= 100 -> String.format(Locale.US, "%.0f", v)
            a >= 10 -> String.format(Locale.US, "%.1f", v)
            else -> String.format(Locale.US, "%.2f", v)
        }
        return "$s $unit"
    }

    /** A "nice" scale-bar length (1, 2 or 5 x 10^n) not longer than [maxM]. */
    fun niceLength(maxM: Double): Double {
        if (maxM <= 0) return 1.0
        val e = 10.0.pow(kotlin.math.floor(log10(maxM)))
        val f = maxM / e
        val n = when {
            f >= 5 -> 5.0
            f >= 2 -> 2.0
            else -> 1.0
        }
        return n * e
    }
}
