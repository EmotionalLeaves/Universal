package com.emotionalleaves.universe.engine

import android.content.Context
import com.emotionalleaves.universe.core.Units
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Extra information for info cards: physics worked out from each object's properties (for real
 * and generated objects alike) and short "About" texts (Wikipedia summaries for named objects,
 * plain explanations for everything else).
 */
object Describe {
    private const val G = 6.674e-11
    private const val M_EARTH = 5.972e24
    private const val G_EARTH = 9.81

    // ------------------------------------------------------------------ About texts

    class About(val text: String, val credit: String)

    @Volatile private var wiki: Map<String, Pair<String, String>> = emptyMap()

    /** Loads wiki/about.tsv (key, Wikipedia title, summary). Missing file = no summaries. */
    fun load(context: Context) {
        val out = HashMap<String, Pair<String, String>>()
        try {
            context.assets.open("wiki/about.tsv").bufferedReader().useLines { lines ->
                for ((n, line) in lines.withIndex()) {
                    if (n == 0) continue
                    val p = line.split('\t')
                    if (p.size >= 3) out[p[0]] = p[1] to p[2]
                }
            }
        } catch (_: Exception) {
        }
        wiki = out
    }

    val wikiCount get() = wiki.size

    private fun wikiFor(vararg keys: String): About? {
        for (k in keys) {
            if (k.isBlank()) continue
            wiki[k.lowercase(Locale.US)]?.let { return About(it.second, "Wikipedia: ${it.first} (CC BY-SA 4.0)") }
        }
        return null
    }

    fun about(b: Body): About? {
        val names = ArrayList<String>()
        names.add(b.name)
        if (b is DsoBody && b.messier.isNotEmpty()) names.add("messier ${b.messier.trimStart('0')}")
        if (b is DsoBody) names.add(b.catalogName)
        if (b is SolarBody && b.id == "sol:sun") names.add("sun")
        wikiFor(*names.toTypedArray())?.let { return it }
        val text = generic(b) ?: return null
        return About(text, if (b.source == Source.GENERATED) "Generated object, described from its type" else "")
    }

    /** One or two plain sentences about what kind of thing this is. */
    private fun generic(b: Body): String? = when (b) {
        is StarBody -> when (starType(b)) {
            "Red dwarf" -> "A red dwarf: a small, cool, dim star. Three out of four stars are like this. They burn so slowly that none has died yet since the universe began."
            "Orange dwarf" -> "An orange dwarf, a little smaller and cooler than the Sun. These stars live 15–30 billion years and are good places to look for life."
            "Yellow dwarf" -> "A yellow dwarf like our Sun: a middle-sized star that fuses hydrogen for about 10 billion years."
            "White star" -> "A white main-sequence star, hotter and brighter than the Sun but shorter-lived (one to a few billion years)."
            "Blue-white star" -> "A hot blue-white star. Rare and very bright, it will burn out in a few hundred million years."
            "Blue giant" -> "A massive blue star thousands to millions of times brighter than the Sun. It will explode as a supernova within a few million years."
            "Red giant" -> "A red giant: an old star that has run out of hydrogen in its core and swollen up. The Sun will become one in about 5 billion years."
            "Supergiant" -> "A supergiant, one of the largest and brightest kinds of star. It will end in a supernova, leaving a neutron star or black hole."
            "White dwarf" -> "A white dwarf: the hot, Earth-sized core left behind by a dead Sun-like star. A teaspoon of it would weigh tonnes."
            else -> null
        }
        is PlanetBody -> when (b.type) {
            PlanetType.LAVA -> "A lava world: so close to its star that the surface rock is molten."
            PlanetType.ROCKY -> "A rocky planet with a solid surface, made of rock and metal like Earth or Mars."
            PlanetType.OCEAN -> "An ocean world: a planet covered by a deep global sea."
            PlanetType.ICE -> "An icy world, too cold for liquid water on the surface."
            PlanetType.SUPER_EARTH -> "A super-Earth: rocky, but bigger and heavier than Earth. There are none in our solar system."
            PlanetType.MINI_NEPTUNE -> "A mini-Neptune: a small planet wrapped in a thick hydrogen atmosphere. The most common kind of planet found so far, yet none orbits the Sun."
            PlanetType.ICE_GIANT -> "An ice giant like Uranus or Neptune: water, ammonia and methane under a hydrogen atmosphere."
            PlanetType.GAS_GIANT -> "A gas giant like Jupiter, mostly hydrogen and helium with no solid surface."
            PlanetType.HOT_JUPITER -> "A hot Jupiter: a gas giant orbiting so close to its star that its year lasts only days."
        }
        is GalaxyBody -> when (b.model.type) {
            GalaxyType.SPIRAL -> "A spiral galaxy: a flat, rotating disc of stars, gas and dust with arms where new stars form, around a bulge of old stars."
            GalaxyType.BARRED -> "A barred spiral galaxy, like the Milky Way: the spiral arms start from the ends of a bar of stars through the centre."
            GalaxyType.LENTICULAR -> "A lenticular galaxy: a disc without spiral arms, with little gas left for new stars."
            GalaxyType.ELLIPTICAL -> "An elliptical galaxy: a smooth ball of mostly old, reddish stars with little gas. The biggest galaxies are like this."
            GalaxyType.IRREGULAR -> "An irregular galaxy with no clear shape, often pulled about by a bigger neighbour."
            GalaxyType.DWARF -> "A dwarf galaxy: a small galaxy of millions to a few billion stars. They are by far the most common kind."
        }
        is SmallBody -> if (b.comet) "A comet: a lump of ice and dust from the outer solar system. Near the Sun it heats up and grows a glowing head and tails."
        else "An asteroid: a rocky or metallic leftover from the formation of the planets."
        is ExoticBody -> b.about
        is RemnantBody -> b.about
        is UniverseBody -> "Everything we can see: light from beyond this edge has not had time to reach us in the 13.8 billion years since the Big Bang."
        else -> null
    }

    // ------------------------------------------------------------------ physics

    fun starType(s: StarBody): String {
        val m = s.absMag
        val t = s.teff
        return when {
            s.spect.startsWith("D") || (m > 9.5 && t > 5000) -> "White dwarf"
            m < -4.5 -> "Supergiant"
            s.spect.contains("III") || s.spect.contains("II") || (t < 5300 && m < 1.5) -> "Red giant"
            t > 20000 -> "Blue giant"
            t > 10000 -> "Blue-white star"
            t > 7300 -> "White star"
            t > 5300 -> "Yellow dwarf"
            t > 3900 -> "Orange dwarf"
            else -> "Red dwarf"
        }
    }

    private fun colorName(t: Double) = when {
        t > 25000 -> "Blue"
        t > 10000 -> "Blue-white"
        t > 7300 -> "White"
        t > 6000 -> "Yellow-white"
        t > 5200 -> "Yellow"
        t > 3800 -> "Orange"
        else -> "Red"
    }

    fun starFacts(s: StarBody): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val type = starType(s)
        out.add("Type" to type)
        out.add("Colour" to colorName(s.teff))
        val l = s.luminosity
        val mass = when (type) {
            "White dwarf" -> 0.6
            "Red giant" -> 1.2
            "Supergiant" -> (l / 3.2e4).pow(1 / 2.5).coerceIn(10.0, 60.0)
            else -> if (l < 0.03) (l / 0.23).pow(1 / 2.3) else if (l < 16) l.pow(1 / 4.0) else (l / 1.4).pow(1 / 3.5)
        }.coerceIn(0.09, 150.0)
        out.add("Mass" to String.format(Locale.US, "about %s × Sun", StarBody.fmtSolar(mass)))
        if (type != "White dwarf" && type != "Red giant" && type != "Supergiant") {
            val life = 10.0 * mass / l
            out.add("Lifetime" to when {
                life > 1000 -> "over a trillion years (far longer than the universe has existed)"
                life >= 1 -> String.format(Locale.US, "about %.0f billion years", life)
                else -> String.format(Locale.US, "about %.0f million years", life * 1000)
            })
            val inner = sqrt(l / 1.1); val outer = sqrt(l / 0.53)
            out.add("Habitable zone" to String.format(Locale.US, "%s – %s AU from the star", fmtAu(inner), fmtAu(outer)))
        }
        if (s.galaxyName == "Milky Way") {
            val d = (s.pos - com.emotionalleaves.universe.core.UPos.ZERO).length()
            if (d > Units.LY) out.add("Its light" to "left it ${Units.lightTime(d)} before reaching Earth")
        } else out.add("Galaxy" to s.galaxyName)
        return out
    }

    private fun fmtAu(v: Double) = when {
        v < 0.1 -> String.format(Locale.US, "%.3f", v)
        v < 10 -> String.format(Locale.US, "%.2f", v)
        else -> String.format(Locale.US, "%.0f", v)
    }

    /** Gravity, escape speed and your weight there, from mass (kg) and radius (m). */
    fun gravityFacts(massKg: Double, radiusM: Double): List<Pair<String, String>> {
        if (massKg <= 0 || radiusM <= 0) return emptyList()
        val g = G * massKg / (radiusM * radiusM)
        val vEsc = sqrt(2 * G * massKg / radiusM)
        val density = massKg / (4.0 / 3 * Math.PI * radiusM.pow(3)) / 1000
        return listOf(
            "Gravity" to String.format(Locale.US, "%.2f × Earth (%.1f m/s²)", g / G_EARTH, g),
            "A 70 kg person weighs" to String.format(Locale.US, "%s kg there", if (g / G_EARTH * 70 >= 10) String.format(Locale.US, "%,.0f", g / G_EARTH * 70) else String.format(Locale.US, "%.2f", g / G_EARTH * 70)),
            "Escape speed" to String.format(Locale.US, "%.1f km/s", vEsc / 1000),
            "Density" to String.format(Locale.US, "%.1f g/cm³", density),
        )
    }

    fun planetFacts(p: PlanetBody): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val massMe = if (p.massMe > 0) p.massMe else when (p.type) {
            PlanetType.GAS_GIANT, PlanetType.HOT_JUPITER -> 300.0
            PlanetType.ICE_GIANT -> 16.0
            PlanetType.MINI_NEPTUNE -> 7.0
            else -> (p.radius / Units.R_EARTH).pow(3.5).coerceIn(0.01, 20.0)
        }
        out.addAll(gravityFacts(massMe * M_EARTH, p.radius))
        if (p.host == null) {
            val l = p.star.luminosity
            val au = p.aM / Units.AU
            val zone = when {
                au < sqrt(l / 1.1) -> "Too hot (inside the habitable zone)"
                au > sqrt(l / 0.53) -> "Too cold (outside the habitable zone)"
                else -> "In the habitable zone: liquid water possible"
            }
            out.add("Habitable zone" to zone)
            out.add("Starlight" to String.format(Locale.US, "%s × what Earth gets", StarBody.fmtSolar(l / (au * au))))
        }
        if (p.teq > 0) {
            val c = p.teq - 273.15
            out.add("Could you stand there?" to when {
                !p.solid -> "No: there is no solid surface"
                c > 100 -> "No: too hot"
                c < -60 -> "Only in a heated spacesuit: far too cold"
                p.hasAtmosphere -> "With a spacesuit and air supply"
                else -> "With a spacesuit: no air"
            })
        }
        return out
    }
}
