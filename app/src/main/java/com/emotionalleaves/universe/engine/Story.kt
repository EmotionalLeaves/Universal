package com.emotionalleaves.universe.engine

import com.emotionalleaves.universe.core.Astro
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A short, vivid description for every object, real or generated, written from its own numbers:
 * what you would see there, how big it is compared with things you know, and how old its light is.
 */
object Story {
    private const val G = 6.674e-11
    private const val THIS_YEAR = 2026

    private fun f0(v: Double) = String.format(Locale.US, "%,.0f", v)
    private fun f1(v: Double) = String.format(Locale.US, "%,.1f", v)
    private fun sig(v: Double): String = when {
        v >= 100 -> f0(v)
        v >= 10 -> f1(v).removeSuffix(".0")
        v >= 1 -> String.format(Locale.US, "%.1f", v).removeSuffix(".0")
        v >= 0.1 -> String.format(Locale.US, "%.2f", v)
        else -> String.format(Locale.US, "%.3f", v)
    }

    private fun count(n: Double): String = when {
        n >= 1e6 -> Units.bigCount(n)
        else -> f0(n)
    }

    // ------------------------------------------------------------------ shared phrases

    /** When the light now arriving at Earth set off, put into human history. */
    fun lightLeft(metres: Double): String? {
        val years = metres / Units.LY
        if (years < 0.5) return null
        val y = THIS_YEAR - years
        return when {
            years < 130 -> "in ${y.toInt()}"
            years < 560 -> "around ${y.toInt()}, before cars, telephones or electric light"
            years < 2000 -> "around the year ${(y / 10).toInt() * 10}"
            years < 5000 -> "in ancient times, when the first cities and pyramids were being built"
            years < 12_000 -> "as the last ice age ended and people first began to farm"
            years < 300_000 -> "during the Ice Age, when mammoths roamed the Earth"
            years < 7e6 -> "before modern humans existed"
            years < 66e6 -> "not long after the dinosaurs died out"
            years < 250e6 -> "when dinosaurs walked the Earth"
            years < 540e6 -> "before the dinosaurs, when the first animals were crawling out of the sea"
            years < 4.5e9 -> "before any complex life existed on Earth"
            else -> "before the Sun and the Earth had even formed"
        }
    }

    private fun lightSentence(b: Body, pos: UPos): String? {
        val d = (pos - UPos.ZERO).length()
        val when_ = lightLeft(d) ?: return null
        return "The light you see from it set off $when_."
    }

    /** Something familiar about as wide as [km]. */
    private fun widthLike(km: Double): String = when {
        km < 0.3 -> "a few football pitches"
        km < 2 -> "a small village"
        km < 8 -> "a small town"
        km < 40 -> "a big city"
        km < 150 -> "a small country"
        km < 600 -> "a country like the United Kingdom"
        km < 1500 -> "Texas"
        km < 3600 -> "the United States"
        km < 15_000 -> "a whole continent"
        else -> "several Earths side by side"
    }

    private fun temperatureLike(c: Double): String = when {
        c > 1000 -> "hot enough to melt rock"
        c > 330 -> "hot enough to melt lead"
        c > 100 -> "hotter than boiling water"
        c > 45 -> "hotter than any desert on Earth"
        c > 0 -> "mild enough for liquid water"
        c > -90 -> "freezing, like Antarctica in winter"
        c > -180 -> "far colder than anywhere on Earth"
        else -> "cold enough to freeze the air itself"
    }

    private fun colourWord(t: Double) = when {
        t > 25000 -> "blue"
        t > 10000 -> "blue-white"
        t > 7300 -> "white"
        t > 6000 -> "yellow-white"
        t > 5200 -> "yellow"
        t > 3800 -> "orange"
        else -> "deep red-orange"
    }

    /** The planet whose orbit is nearest to [au], for "it would reach past Mars" comparisons. */
    private fun orbitLandmark(au: Double): String? = when {
        au < 0.25 -> null
        au < 0.55 -> "Mercury"
        au < 0.86 -> "Venus"
        au < 1.25 -> "Earth"
        au < 3.0 -> "Mars"
        au < 7.0 -> "Jupiter"
        au < 14.0 -> "Saturn"
        au < 25.0 -> "Uranus"
        else -> "Neptune"
    }

    private fun jump(gRel: Double): String {
        val h = 0.5 / gRel
        return when {
            gRel < 0.02 -> "one hop would launch you off into space"
            h >= 10 -> "a jump would carry you ${f0(h)} m into the air"
            h >= 1 -> "a jump would carry you ${sig(h)} m high"
            else -> "you could barely jump ${f0(h * 100)} cm"
        }
    }

    // ------------------------------------------------------------------ stories

    fun of(b: Body, jd: Double): String = try {
        when (b) {
            is SolarBody -> solar(b, jd)
            is StarBody -> star(b)
            is PlanetBody -> planet(b)
            is GalaxyBody -> galaxy(b)
            is SmallBody -> small(b, jd)
            is RockBody -> rock(b)
            is BeltBody -> "A ${if (b.belt.icy) "lump of ice and rock" else "rock"} about ${sig(b.radius * 2 / 1000)} km across — as wide as ${widthLike(b.radius * 2 / 1000)} — " +
                "one of countless bodies in ${b.belt.star.name}'s ${b.belt.label}, leftovers from when its planets formed. " +
                "From here, ${b.belt.star.name} shines ${colourWord(b.belt.star.teff)}, ${sig(b.belt.localAu(b.index).length())} AU away."
            is RemnantBody -> remnant(b)
            is ExoticBody -> exotic(b)
            is DsoBody -> dso(b)
            is UniverseBody -> "This app holds about ${Units.sci(Census.total(true))} things you can visit — every star, planet, moon and rock down to 1 m, " +
                "here and in the imagined universe beyond the edge. " +
                "The part we can actually see: around 2 trillion galaxies, " +
                "spread along a web of filaments and walls around vast empty voids. Because space has been stretching all along, " +
                "its edge is now about 46 billion light years away in every direction."
            else -> ""
        }
    } catch (_: Exception) {
        ""
    }

    private fun star(s: StarBody): String {
        val type = Describe.starType(s)
        val sb = StringBuilder()
        val rSun = s.radius / Units.R_SUN
        val l = s.luminosity
        val t = type.lowercase(Locale.US)
        val hasColour = listOf("red", "orange", "yellow", "white", "blue").any { t.contains(it) }
        sb.append(if (hasColour) "A $t" else "A ${colourWord(s.teff)} $t")
        sb.append(
            when {
                rSun > 5 -> {
                    val au = s.radius / Units.AU
                    val mark = orbitLandmark(au)
                    if (mark != null) " so huge that, in the Sun's place, it would swallow everything out to the orbit of $mark." else " ${f0(rSun)} times wider than the Sun."
                }
                rSun > 1.2 -> ", ${sig(rSun)} times as wide as the Sun"
                rSun > 0.8 -> " about the size of our Sun"
                rSun > 0.05 -> ", only ${f0(rSun * 100)}% of the Sun's width"
                else -> " barely bigger than the Earth, yet it weighs about as much as the Sun"
            },
        )
        if (!sb.endsWith(".")) {
            sb.append(
                when {
                    l > 2 -> ", shining ${count(l)} times brighter."
                    l > 0.5 -> "."
                    else -> ", glowing just ${sig(l * 100)}% as bright."
                },
            )
        }
        if (type == "Red dwarf") sb.append(" Stars like this burn so slowly they will outlive every Sun-like star in the universe.")
        if (type == "Supergiant" || type == "Blue giant") sb.append(" It lives fast: within a few million years it will explode as a supernova.")
        if (type == "Red giant") sb.append(" It is near the end of its life; one day it will puff off its outer layers and leave a white dwarf.")
        val hzIn = sqrt(l / 1.1); val hzOut = sqrt(l / 0.53)
        if (type !in setOf("White dwarf", "Supergiant", "Red giant")) {
            sb.append(
                if (hzOut < 0.1) " A planet would need to hug it at ${sig(hzIn)}–${sig(hzOut)} AU to stay warm enough for liquid water."
                else " Liquid water could exist on planets ${sig(hzIn)}–${sig(hzOut)} AU from it.",
            )
        }
        if (s.galaxyName == "Milky Way") lightSentence(s, s.pos)?.let { sb.append(" ").append(it) }
        else sb.append(" It lies in ${s.galaxyName}, far beyond our own galaxy.")
        return sb.toString()
    }

    private fun planet(p: PlanetBody): String {
        val sb = StringBuilder()
        val re = p.radius / Units.R_EARTH
        val massMe = if (p.massMe > 0) p.massMe else Describe.estimatedMassMe(p)
        val gRel = massMe / (re * re)
        val host = p.host
        if (host == null) {
            val au = p.aM / Units.AU
            val year = p.periodDays
            sb.append(
                when {
                    re > 6 -> "A giant world ${sig(re)} times as wide as Earth"
                    re > 1.3 -> "A world ${sig(re)} times as wide as Earth"
                    re > 0.8 -> "An Earth-sized world"
                    else -> "A small world, ${f0(re * 100)}% of Earth's width"
                },
            )
            sb.append(
                if (year < 2) ", racing around its star in just ${f1(year * 24)} hours."
                else if (year < 800) ", circling its star every ${f0(year)} days."
                else ", taking ${f1(year / 365.25)} Earth years to circle its star.",
            )
            // How the star looks from here, compared with our Sun from Earth.
            val ang = (p.star.radius / Units.R_SUN) / au
            sb.append(
                when {
                    ang > 3 -> " The ${colourWord(p.star.teff)} star fills the sky, ${f0(ang)} times wider than our Sun looks."
                    ang > 1.3 -> " Its ${colourWord(p.star.teff)} sun looks ${sig(ang)} times as wide as ours."
                    ang > 0.7 -> " Its ${colourWord(p.star.teff)} sun looks about as big as ours."
                    else -> " Its ${colourWord(p.star.teff)} sun is a small disc, ${f0(ang * 100)}% the width of ours."
                },
            )
        } else {
            sb.append("A moon of ${host.name}, ${f0(p.radius * 2 / 1000)} km across — about as wide as ${widthLike(p.radius * 2 / 1000)}.")
            sb.append(" ${host.name} hangs in its sky, ${sig(Math.toDegrees(2 * host.radius / p.aM))}° across (our Moon looks 0.5°).")
        }
        if (p.teq > 0) sb.append(" The surface is about ${f0(p.teq - 273.15)} °C — ${temperatureLike(p.teq - 273.15)}.")
        if (p.solid) sb.append(" Gravity is ${sig(gRel)} times Earth's: ${jump(gRel)}.")
        else sb.append(" There is no ground to stand on: you would sink through ever-thicker gas.")
        if (p.ringOuter > 0) sb.append(" Rings arc across its sky.")
        if (p.moons.isNotEmpty()) sb.append(" ${p.moons.size} moon${if (p.moons.size > 1) "s" else ""} orbit it.")
        return sb.toString()
    }

    private fun solar(b: SolarBody, jd: Double): String {
        val sb = StringBuilder()
        val km = b.radius * 2 / 1000
        when (b.kind) {
            Kind.STAR -> return "Our star: 1.4 million km wide, big enough to hold 1.3 million Earths. Its light takes 8 minutes 20 seconds to reach us, " +
                "and it has been shining for 4.6 billion years — about halfway through its life."
            Kind.MOON -> {
                val p = b.parent ?: return ""
                sb.append("A moon of ${p.name}, ${f0(km)} km across — about as wide as ${widthLike(km)}.")
                val d = b.relative(jd).length()
                val ang = Math.toDegrees(2 * p.radius / d)
                if (ang > 0.2) sb.append(" From its surface, ${p.name} would look ${sig(ang)}° wide — ${sig(ang / 0.5)} times bigger than the full Moon in our sky.")
                sb.append(" It goes around ${p.name} every ${SolarBody.period(abs(b.orbitPeriodDays))}.")
            }
            else -> {
                if (b.id == "sol:earth") return "Home: the only world known to have life. Seen from Mars it is a bright blue evening star; " +
                    "seen from beyond Neptune, a pale blue dot. Sunlight takes about 8 minutes to get here, and the Moon is 1.3 light seconds away."
                val re = b.radius / Units.R_EARTH
                sb.append(if (re > 1.5) "${sig(re)} times as wide as Earth" else if (re > 0.9) "Almost exactly Earth's size" else "${f0(re * 100)}% of Earth's width")
                val au = b.heliocentric(jd).length() / Units.AU
                sb.append(", and ${sig(au)} AU from the Sun right now — sunlight takes ${f0(au * 499 / 60)} minutes to get there.")
                if (b.children.isNotEmpty()) sb.append(" ${b.children.size} known moon${if (b.children.size > 1) "s" else ""}.")
            }
        }
        if (b.massKg > 0 && b.radius > 0) {
            val gRel = G * b.massKg / (b.radius * b.radius) / 9.81
            if (b.id !in setOf("sol:jupiter", "sol:saturn", "sol:uranus", "sol:neptune")) sb.append(" With ${sig(gRel)} g, ${jump(gRel)}.")
        }
        return sb.toString()
    }

    private fun galaxy(g: GalaxyBody): String {
        val sb = StringBuilder()
        if (g.isMilkyWay) return "Our home galaxy: 100 to 400 billion stars in a barred spiral disc about 100,000 light years across. " +
            "The Sun sits in a minor spiral arm 26,700 light years from the centre, where a black hole of 4 million Suns lies hidden behind dust. " +
            "Fly in and every one of its stars can be visited, planets and all."
        val stars = 10.0.pow(-0.4 * (g.absMag + 21.0)) * 1.0e11
        val ly = g.radiusPc * 2 * 3.2616
        sb.append("${count(stars).replaceFirstChar { it.uppercase() }} stars in a ${g.model.type.label.lowercase(Locale.US)} ${f0(ly)} light years across")
        sb.append(if (g.isMilkyWay) " — our home. The Sun is one of them, about 26,700 light years from the centre." else ".")
        if (!g.isMilkyWay) {
            sb.append(" Light needs ${f0(ly)} years just to cross it.")
            lightSentence(g, g.center)?.let { sb.append(" ").append(it) }
        }
        sb.append(" Fly in and every one of its stars can be visited, planets and all.")
        return sb.toString()
    }

    private fun small(b: SmallBody, jd: Double): String {
        val sb = StringBuilder()
        val km = b.radius * 2 / 1000
        if (b.comet) {
            sb.append("A mountain of ice and dust a few km across, on a long looping orbit.")
        } else {
            sb.append("${if (km > 100) "A giant rock" else "A chunk of rock"} about ${sig(km)} km across — as wide as ${widthLike(km)}.")
        }
        val facts = b.facts(jd)
        facts.firstOrNull { it.first == "Orbit" }?.let { (_, v) -> sb.append(" Its orbit runs $v.") }
        if (km < 50 && !b.comet) sb.append(" Its gravity is so weak you could throw a ball into orbit.")
        if (b.comet) sb.append(" Each time it swings close to the Sun, the ice turns to gas and blows out a glowing tail millions of km long.")
        return sb.toString()
    }

    private fun rock(b: RockBody): String {
        val sizeText = if (b.km >= 1) "${sig(b.km)} km" else "${f0(b.km * 1000)} m"
        val like = when {
            b.km < 0.003 -> "about the size of a car"
            b.km < 0.03 -> "as big as a house"
            b.km < 0.3 -> "the size of a stadium"
            else -> "as wide as ${widthLike(b.km)}"
        }
        val where = if (b.zone.key == "sun") "the Sun's ${b.zone.label}" else "${b.zone.starName}'s ${b.zone.label}"
        return "A ${if (b.zone.icy) "dirty snowball" else "lump of rock"} $sizeText across — $like — drifting in $where, " +
            "${sig(b.distanceAu)} AU from its star. It is one of about ${Units.bigCount(b.zone.total())} bodies there down to the size of a person. " +
            (if (b.km < 0.05) "Its gravity is so feeble a gentle push would send you off it for good." else "Nobody has ever seen it: it was generated by the same size law real surveys measure.")
    }

    private fun remnant(b: RemnantBody): String = when (b.kind) {
        Kind.CLUSTER -> {
            val n = b.facts(0.0).firstOrNull { it.first == "Stars" }?.second ?: "hundreds of"
            "A family of $n stars born together from the same cloud, ${f0(b.radius * 2 / Units.LY)} light years across. " +
                "Seen from a planet inside it, the night sky would blaze with dozens of stars brighter than Venus. " +
                "Over a few hundred million years the galaxy's tides will scatter them."
        }
        Kind.NEBULA -> "A cloud of hydrogen ${f0(b.radius * 2 / Units.LY)} light years across, glowing red where newborn stars light it up. " +
            "Inside, gravity is pulling clumps of gas together into the next generation of suns and planets."
        Kind.BLACK_HOLE -> {
            val mass = b.facts(0.0).firstOrNull { it.first == "Mass" }?.second ?: "several × Sun"
            "Nothing escapes from here — not even light. ${mass.replace(" × Sun", " times the Sun's mass")} is packed inside an event horizon only ${f0(b.radius * 2 / 1000)} km across, " +
                "smaller than a city. Near it, starlight from behind is bent into rings, and time itself runs slow."
        }
        Kind.NEUTRON_STAR -> "The crushed core of an exploded star: more than the Sun's mass squeezed into a ball 23 km wide. " +
            "Its gravity is a hundred billion times Earth's, and a teaspoon of it would weigh as much as a mountain."
        else -> "A planet with no sun. Thrown out of the system it was born in, it drifts alone through the dark between the stars. " +
            "Its sky is permanent night; the nearest star is just one point of light among millions."
    }

    private fun exotic(b: ExoticBody): String = when (b.kind) {
        Kind.PULSAR -> {
            val spin = b.facts(0.0).firstOrNull { it.first == "Spins" }?.second ?: ""
            "A city-sized neutron star spinning $spin, sweeping beams of radio waves across space like a lighthouse. " +
                "Its ticks are so regular they rival atomic clocks. " + (lightSentence(b, b.position(0.0)) ?: "")
        }
        Kind.QUASAR -> "The blazing heart of a young galaxy: gas spiralling into a black hole billions of times the Sun's mass, " +
            "outshining all the galaxy's stars put together. " + (lightSentence(b, b.position(0.0)) ?: "")
        Kind.GALAXY_CLUSTER -> "One of the biggest things held together by gravity: hundreds of galaxies swarming inside a cloud of gas " +
            "millions of degrees hot. Most of its mass is invisible dark matter. " + (lightSentence(b, b.position(0.0)) ?: "")
        else -> b.about
    }

    private fun dso(d: DsoBody): String {
        val ly = d.radius * 2 / Units.LY
        val what = when (d.type) {
            "OCl" -> "A young family of stars born together from the same cloud"
            "GCl" -> "A ball of hundreds of thousands of ancient stars, older than the Sun by billions of years"
            "PN" -> "The glowing shell a dying Sun-like star threw off, lit up by the hot core left behind"
            "SNR" -> "The expanding wreckage of a star that exploded"
            "HII", "EmN", "Cl+N" -> "A cloud of hydrogen glowing red where new stars are being born"
            "RfN" -> "Dust lit up by nearby stars, like fog in a car's headlights"
            "DrkN" -> "A cold, dark cloud of dust blotting out the stars behind it"
            else -> "A cloud of gas and dust between the stars"
        }
        val sb = StringBuilder("$what, ${if (ly >= 1) f0(ly) + " light years" else "less than a light year"} across.")
        lightSentence(d, d.position(0.0))?.let { sb.append(" ").append(it) }
        return sb.toString()
    }
}
