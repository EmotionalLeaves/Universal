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

    /** "1.2 billion", "350 million", "4,500" — for counts of stars, planets, galaxies. */
    fun bigCount(n: Double): String = when {
        n >= 1e15 -> String.format(Locale.US, "%.1f quadrillion", n / 1e15)
        n >= 1e12 -> String.format(Locale.US, "%.1f trillion", n / 1e12)
        n >= 1e9 -> String.format(Locale.US, "%.1f billion", n / 1e9)
        n >= 1e6 -> String.format(Locale.US, "%.0f million", n / 1e6)
        else -> String.format(Locale.US, "%,.0f", n)
    }

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

    /** How long light takes to cross [m] metres, in words ("8.3 minutes", "2.5 million years"). */
    fun lightTime(m: Double): String {
        val s = m / 299_792_458.0
        val years = s / 31_557_600.0
        return when {
            s < 1 -> String.format(Locale.US, "%.2f seconds", s)
            s < 120 -> String.format(Locale.US, "%.0f seconds", s)
            s < 7200 -> String.format(Locale.US, "%.1f minutes", s / 60)
            s < 172_800 -> String.format(Locale.US, "%.1f hours", s / 3600)
            years < 2 -> String.format(Locale.US, "%.0f days", s / 86400)
            years < 1e6 -> String.format(Locale.US, "%,.0f years", years)
            years < 1e9 -> String.format(Locale.US, "%.1f million years", years / 1e6)
            else -> String.format(Locale.US, "%.1f billion years", years / 1e9)
        }
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
