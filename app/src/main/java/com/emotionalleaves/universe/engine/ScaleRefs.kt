package com.emotionalleaves.universe.engine

import com.emotionalleaves.universe.core.Units
import java.util.Locale

/** Familiar sizes for scale: from a person to the observable universe (widths in metres). */
object ScaleRefs {
    class Ref(val name: String, val metres: Double, val note: String, val bodyId: String? = null)

    val ladder: List<Ref> = listOf(
        Ref("A person", 1.7, "about 1.7 m tall"),
        Ref("Blue whale", 30.0, "the largest animal ever"),
        Ref("Eiffel Tower", 330.0, "330 m"),
        Ref("Mount Everest", 8_849.0, "8.8 km high"),
        Ref("Great Britain", 1_000_000.0, "about 1,000 km long"),
        Ref("Australia", 4_000_000.0, "4,000 km across"),
        Ref("The Moon", 3_474_800.0, "3,475 km wide", "sol:moon"),
        Ref("Earth", 12_742_000.0, "12,742 km wide", "sol:earth"),
        Ref("Jupiter", 139_820_000.0, "11 Earths wide", "sol:jupiter"),
        Ref("Earth–Moon distance", 384_400_000.0, "384,400 km"),
        Ref("The Sun", 1_392_700_000.0, "109 Earths wide", "sol:sun"),
        Ref("Betelgeuse", 1_640 * Units.R_SUN, "a red supergiant, ~800 Suns wide"),
        Ref("Earth's orbit", 2 * Units.AU, "2 AU across"),
        Ref("UY Scuti", 1_818 * Units.R_SUN, "one of the largest stars", "fam:uyscuti"),
        Ref("Stephenson 2-18", 4_300 * Units.R_SUN, "perhaps the largest star", "fam:st218"),
        Ref("Solar System (Neptune's orbit)", 60 * Units.AU, "60 AU across"),
        Ref("TON 618's event horizon", 2 * 4.07e10 * 2953.25, "a 40-billion-Sun black hole", "fam:ton618"),
        Ref("Oort cloud", 3.0 * Units.LY, "the Sun's outer comet cloud, ~3 light years"),
        Ref("Sun to Proxima Centauri", 4.24 * Units.LY, "the nearest star, 4.24 light years"),
        Ref("Orion Nebula", 24 * Units.LY, "a star nursery, 24 light years"),
        Ref("Omega Centauri", 150 * Units.LY, "the biggest globular cluster"),
        Ref("Milky Way", 100_000 * Units.LY, "our galaxy, 100,000 light years", "gal:milkyway"),
        Ref("Andromeda Galaxy", 220_000 * Units.LY, "220,000 light years"),
        Ref("Local Group", 10e6 * Units.LY, "the Milky Way's neighbourhood, 10 million ly"),
        Ref("Boötes Void", 124 * Units.MPC, "an almost empty bubble, 400 million ly", "fam:bootes"),
        Ref("Laniakea Supercluster", 160 * Units.MPC, "our supercluster, 520 million ly", "fam:laniakea"),
        Ref("Sloan Great Wall", 420 * Units.MPC, "a wall of galaxies, 1.4 billion ly", "fam:sloanwall"),
        Ref("Observable universe", 2 * Units.UNIVERSE_RADIUS, "93 billion light years", "universe"),
    ).sortedBy { it.metres }

    /** "≈ 3 × Earth" style comparison for a width, using the closest familiar thing. */
    fun like(metres: Double, excludeId: String? = null): String {
        if (metres <= 0) return ""
        val r = ladder.filter { excludeId == null || it.bodyId != excludeId }.minByOrNull { kotlin.math.abs(kotlin.math.ln(metres / it.metres)) } ?: return ""
        val k = metres / r.metres
        val name = r.name.replaceFirst("The ", "the ").replaceFirst("A person", "a person")
        return when {
            k in 0.8..1.25 -> "≈ $name"
            k > 1 -> "≈ ${times(k)} × $name"
            else -> "≈ 1/${times(1 / k)} of $name"
        }
    }

    private fun times(k: Double) = if (k < 10) String.format(Locale.US, "%.1f", k).removeSuffix(".0") else String.format(Locale.US, "%,.0f", k)
}
