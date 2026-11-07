package com.emotionalleaves.universe.engine

import android.content.Context
import com.emotionalleaves.universe.core.Astro
import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.core.Rng
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** One confirmed exoplanet row from the NASA Exoplanet Archive. */
class ExoRow(
    val name: String, val host: String, val aAu: Double?, val radiusRe: Double?, val massMe: Double?,
    val periodDays: Double?, val ecc: Double?, val incl: Double?, val teq: Double?,
    val ra: Double, val dec: Double, val distPc: Double?, val stTeff: Double?, val stRad: Double?,
    val stMass: Double?, val stSpect: String, val hd: String, val hip: String, val year: String, val method: String,
)

/** The exoplanet catalogue grouped by host star. */
class ExoCatalog(val byHost: Map<String, List<ExoRow>>) {
    val planetCount = byHost.values.sumOf { it.size }

    companion object {
        fun load(context: Context): ExoCatalog? = try {
            val map = LinkedHashMap<String, MutableList<ExoRow>>()
            context.assets.open("planets/exoplanets.tsv").bufferedReader().useLines { lines ->
                var header: List<String>? = null
                for (l in lines) {
                    val p = l.split("\t")
                    if (header == null) { header = p; continue }
                    val h = header!!
                    fun s(k: String) = p.getOrElse(h.indexOf(k)) { "" }
                    fun d(k: String) = s(k).toDoubleOrNull()
                    val row = ExoRow(
                        s("pl_name"), s("hostname"), d("pl_orbsmax"), d("pl_rade"), d("pl_bmasse"), d("pl_orbper"),
                        d("pl_orbeccen"), d("pl_orbincl"), d("pl_eqt"), d("ra") ?: continue, d("dec") ?: continue,
                        d("sy_dist"), d("st_teff"), d("st_rad"), d("st_mass"), s("st_spectype"), s("hd_name"),
                        s("hip_name"), s("disc_year"), s("discoverymethod"),
                    )
                    map.getOrPut(row.host) { ArrayList() }.add(row)
                }
            }
            ExoCatalog(map)
        } catch (_: Exception) {
            null
        }
    }
}

enum class PlanetType(val label: String, val style: Int) {
    LAVA("Lava world", 3),
    ROCKY("Rocky planet", 0),
    OCEAN("Ocean world", 4),
    ICE("Ice world", 2),
    SUPER_EARTH("Super-Earth", 0),
    MINI_NEPTUNE("Mini-Neptune", 1),
    ICE_GIANT("Ice giant", 1),
    GAS_GIANT("Gas giant", 1),
    HOT_JUPITER("Hot Jupiter", 1),
}

/** A planet around another star: real (from the archive) or generated. */
class PlanetBody(
    override val id: String,
    override val name: String,
    val star: StarBody,
    val aM: Double,
    val ecc: Double,
    private val plane: Quatd,
    private val m0: Double,
    val periodDays: Double,
    override val radius: Double,
    val massMe: Double,
    val type: PlanetType,
    val teq: Double,
    val palette: IntArray,
    val seed: Long,
    val hasAtmosphere: Boolean,
    override val source: Source,
    private val extra: List<Pair<String, String>>,
    /** For moons: the planet they orbit. */
    val host: PlanetBody? = null,
    /** Ring outer radius in planet radii (0 = no rings). */
    val ringOuter: Double = 0.0,
) : Body() {
    override val kind = if (host != null) Kind.MOON else Kind.PLANET
    override val color = palette[0]
    /** Generated moons (filled in by SystemFactory). */
    var moons: List<PlanetBody> = emptyList()
    val solid get() = type == PlanetType.LAVA || type == PlanetType.ROCKY || type == PlanetType.OCEAN ||
        type == PlanetType.ICE || type == PlanetType.SUPER_EARTH

    fun relative(jd: Double): Vec3d {
        val m = m0 + 2 * Math.PI * (jd - Astro.J2000) / periodDays
        val inPlane = Astro.orbitToPlane(aM, ecc, 0.0, 0.0, 0.0, m)
        return plane.rotate(inPlane)
    }

    override fun position(jd: Double): UPos = (host?.position(jd) ?: star.pos) + relative(jd)

    fun spin(jd: Double): Quatd = plane * Quatd.axisAngle(Vec3d.Z, (jd * 2 * Math.PI / (0.4 + (seed and 15) / 10.0)) % (2 * Math.PI))

    override val subtitle get() = if (host != null) "${type.label} · moon of ${host.name}" else "${type.label} orbiting ${star.name}"
    override fun viewDistance() = radius * 3.2

    override fun facts(jd: Double): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out.add("Radius" to String.format(Locale.US, "%.2f × Earth", radius / Units.R_EARTH))
        if (massMe > 0) out.add("Mass" to String.format(Locale.US, "%.2f × Earth", massMe))
        if (host != null) out.add("Orbit" to String.format(Locale.US, "%,.0f km from ${host.name}, %s", aM / 1000, SolarBody.period(periodDays)))
        else out.add("Orbit" to String.format(Locale.US, "%.3f AU, %s", aM / Units.AU, SolarBody.period(periodDays)))
        if (moons.isNotEmpty()) out.add("Moons" to "${moons.size} (generated)")
        if (ringOuter > 0) out.add("Rings" to "Yes (generated)")
        if (teq > 0) out.add("Temperature" to String.format(Locale.US, "about %.0f K (%.0f °C)", teq, teq - 273.15))
        out.addAll(Describe.planetFacts(this))
        out.addAll(extra)
        return out
    }

    companion object {
        /** Mass-radius relation (rough, after Chen & Kipping 2017). Returns Earth radii. */
        fun radiusFromMass(me: Double): Double = when {
            me < 2.0 -> me.pow(0.28)
            me < 130 -> 0.8 * me.pow(0.59)
            else -> 11.0
        }.coerceIn(0.3, 22.0)
    }
}

/** A star plus its planets. */
class StarSystem(val star: StarBody, val planets: List<PlanetBody>, val real: Boolean)

/** Builds systems: real ones from the archive, generated ones from the star's properties (rules 3 and 4). */
object SystemFactory {
    const val GENERATOR_VERSION = 1

    fun real(star: StarBody, rows: List<ExoRow>): StarSystem {
        val rng = Rng(Rng.hashString(star.id))
        val toEarth = (UPos.ZERO - star.pos).normalized()
        val transiting = rows.any { it.method.contains("Transit", true) }
        // Transiting systems are seen edge-on from Earth.
        val normal = if (transiting) Vec3d.perpendicular(toEarth) else rng.unitVector()
        val basePlane = planeFor(normal)
        val sorted = rows.sortedBy { it.aAu ?: it.periodDays ?: 0.0 }
        val starMassSun = rows.firstNotNullOfOrNull { it.stMass } ?: massFromLum(star.luminosity)
        val planets = sorted.map { r ->
            val period = r.periodDays ?: r.aAu?.let { 365.25 * sqrt(it.pow(3) / starMassSun) } ?: 365.0
            val aAu = r.aAu ?: (starMassSun * (period / 365.25).pow(2)).pow(1.0 / 3)
            val re = r.radiusRe ?: r.massMe?.let { PlanetBody.radiusFromMass(it) } ?: 1.0
            val teq = r.teq ?: (278.0 * star.luminosity.pow(0.25) / sqrt(aAu))
            val type = classify(re, teq, rng)
            val tilt = Quatd.axisAngle(Vec3d.X, Math.toRadians(rng.gaussian() * 1.5))
            val extra = listOf(
                "Discovered" to listOf(r.year, r.method).filter { it.isNotEmpty() }.joinToString(", "),
                "Data" to "NASA Exoplanet Archive (looks are generated)",
            )
            PlanetBody(
                "exo:${r.name}", r.name, star, aAu * Units.AU, (r.ecc ?: 0.0).coerceIn(0.0, 0.95),
                basePlane * tilt, rng.nextDouble() * 2 * Math.PI, period, re * Units.R_EARTH, r.massMe ?: 0.0,
                type, teq, palette(type, teq, rng), rng.nextLong(), hasAtmo(type), Source.REAL, extra,
            )
        }
        for (p in planets) addMoons(p, rng)
        return StarSystem(star, planets, true)
    }

    /** Moons for a planet: more and larger around giants (rule 4: parents decide children). */
    fun addMoons(p: PlanetBody, parentRng: Rng) {
        val rng = Rng(Rng.hash(p.seed, 77))
        val n = when (p.type) {
            PlanetType.GAS_GIANT, PlanetType.HOT_JUPITER -> if (p.type == PlanetType.HOT_JUPITER) rng.nextInt(2) else 1 + rng.nextInt(5)
            PlanetType.ICE_GIANT -> 1 + rng.nextInt(4)
            PlanetType.MINI_NEPTUNE -> rng.nextInt(3)
            else -> if (rng.chance(0.25)) 1 else 0
        }
        if (n == 0) return
        val massKg = (if (p.massMe > 0) p.massMe else 1.0) * 5.972e24
        // Keep moons well inside the planet's Hill sphere.
        val hill = p.aM * (massKg / (3 * 1.989e30)).pow(1.0 / 3)
        var a = p.radius * (3.0 + rng.nextDouble() * 4)
        val list = ArrayList<PlanetBody>()
        for (k in 0 until n) {
            if (a > hill * 0.4) break
            val big = p.radius > 3 * Units.R_EARTH
            val r = (if (big) 0.08 + rng.nextDouble() * 0.35 else 0.03 + rng.nextDouble() * 0.2) * Units.R_EARTH
            val period = 2 * Math.PI * sqrt(a * a * a / (6.674e-11 * massKg)) / 86400
            val teq = p.teq
            val type = when {
                k == 0 && big && rng.chance(0.2) -> PlanetType.LAVA
                teq < 200 -> PlanetType.ICE
                else -> PlanetType.ROCKY
            }
            val plane = Quatd.axisAngle(Vec3d.X, Math.toRadians(rng.gaussian() * 3))
            list.add(
                PlanetBody(
                    "${p.id}/m${k + 1}", "${p.name} ${toRoman(k + 1)}", p.star, a, rng.nextDouble() * 0.03, plane,
                    rng.nextDouble() * 2 * Math.PI, period, r, 0.0, type, teq, palette(type, teq, rng), rng.nextLong(),
                    false, Source.GENERATED, listOf("Data" to "Generated moon (generator v$GENERATOR_VERSION)"), host = p,
                ),
            )
            a *= 1.6 + rng.nextDouble() * 1.2
        }
        p.moons = list
    }

    private fun toRoman(n: Int) = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII").getOrElse(n - 1) { n.toString() }

    fun generated(star: StarBody): StarSystem {
        val rng = Rng(Rng.hash(Rng.hashString(star.id), GENERATOR_VERSION.toLong()))
        val t = star.teff
        val giant = star.radius > 8 * Units.R_SUN
        val dwarf = star.radius < 0.05 * Units.R_SUN
        val mean = when {
            dwarf -> 0.6
            giant -> 0.9
            t < 3900 -> 2.6
            t < 5300 -> 2.2
            t < 6000 -> 2.0
            t < 7500 -> 1.5
            t < 10000 -> 1.0
            else -> 0.4
        }
        val n = rng.poisson(mean).coerceAtMost(9)
        val planets = ArrayList<PlanetBody>()
        val massSun = massFromLum(star.luminosity)
        var aAu = (0.02 + rng.nextDouble() * 0.12) * sqrt(star.luminosity).coerceIn(0.05, 30.0)
        if (giant) aAu = max(aAu, star.radius / Units.AU * 3)
        val normal = rng.unitVector()
        val basePlane = planeFor(normal)
        for (k in 0 until n) {
            val teq = 278.0 * star.luminosity.pow(0.25) / sqrt(aAu)
            // Giants are more common beyond the snow line.
            val snow = 2.7 * sqrt(star.luminosity)
            val roll = rng.nextDouble()
            val re = when {
                aAu > snow && roll < 0.35 -> 8.0 + rng.nextDouble() * 6.0
                aAu > snow && roll < 0.55 -> 3.5 + rng.nextDouble() * 2.5
                roll < 0.04 && aAu < 0.1 -> 9.0 + rng.nextDouble() * 6.0
                roll < 0.5 -> 0.5 + rng.nextDouble() * 1.1
                roll < 0.85 -> 1.6 + rng.nextDouble() * 2.2
                else -> 3.8 + rng.nextDouble() * 3.0
            }
            val type = classify(re, teq, rng)
            val mass = when {
                re < 1.6 -> re.pow(3.6)
                re < 4.0 -> 2.7 * re.pow(1.3)
                else -> (re / 11.0).pow(2) * 318 * (0.3 + rng.nextDouble())
            }
            val period = 365.25 * sqrt(aAu.pow(3) / massSun)
            val letter = ('b' + k)
            val tilt = Quatd.axisAngle(Vec3d.X, Math.toRadians(rng.gaussian() * 2.0))
            planets.add(
                PlanetBody(
                    "${star.id}/$letter", "${star.name} $letter", star, aAu * Units.AU, (rng.nextDouble() * 0.12),
                    basePlane * tilt, rng.nextDouble() * 2 * Math.PI, period, re * Units.R_EARTH, mass, type, teq,
                    palette(type, teq, rng), rng.nextLong(), hasAtmo(type), Source.GENERATED,
                    listOf("Data" to "Generated from the star's type (generator v$GENERATOR_VERSION)"),
                    ringOuter = if ((type == PlanetType.GAS_GIANT || type == PlanetType.ICE_GIANT) && rng.chance(0.3)) 1.8 + rng.nextDouble() * 0.8 else 0.0,
                ),
            )
            aAu *= 1.45 + rng.nextDouble() * 0.9
        }
        for (p in planets) addMoons(p, rng)
        return StarSystem(star, planets, false)
    }

    private fun planeFor(normal: Vec3d): Quatd {
        val x = Vec3d.perpendicular(normal)
        return Quatd.fromBasis(x, normal.cross(x).normalized(), normal)
    }

    private fun massFromLum(l: Double) = l.coerceAtLeast(1e-4).pow(1.0 / 3.5).coerceIn(0.08, 60.0)

    fun classify(re: Double, teq: Double, rng: Rng): PlanetType = when {
        re >= 8.0 -> if (teq > 900) PlanetType.HOT_JUPITER else PlanetType.GAS_GIANT
        re >= 4.0 -> PlanetType.ICE_GIANT
        re >= 2.0 -> PlanetType.MINI_NEPTUNE
        teq > 800 -> PlanetType.LAVA
        teq in 240.0..340.0 && rng.chance(0.55) -> PlanetType.OCEAN
        teq < 200 -> PlanetType.ICE
        re > 1.4 -> PlanetType.SUPER_EARTH
        else -> PlanetType.ROCKY
    }

    private fun hasAtmo(t: PlanetType) = t != PlanetType.ROCKY && t != PlanetType.LAVA

    /** Three colours for the planet shader, chosen from type and temperature. */
    fun palette(type: PlanetType, teq: Double, rng: Rng): IntArray {
        fun jitter(c: Int): Int {
            val f = 0.8 + rng.nextDouble() * 0.4
            val r = (((c shr 16) and 255) * f).toInt().coerceIn(0, 255)
            val g = (((c shr 8) and 255) * (0.85 + rng.nextDouble() * 0.3)).toInt().coerceIn(0, 255)
            val b = ((c and 255) * f).toInt().coerceIn(0, 255)
            return (r shl 16) or (g shl 8) or b
        }
        val base = when (type) {
            PlanetType.LAVA -> intArrayOf(0x2A1A14, 0x5A2A18, 0xFF6A1A)
            PlanetType.ROCKY, PlanetType.SUPER_EARTH -> listOf(
                intArrayOf(0x8A6E58, 0x5E4A3C, 0xB09A84), intArrayOf(0x9A4A2E, 0x6A3420, 0xC88A6A),
                intArrayOf(0x7A7A7A, 0x555555, 0xA0A0A0), intArrayOf(0xB09060, 0x806040, 0xD8C090),
            )[rng.nextInt(4)]
            PlanetType.OCEAN -> intArrayOf(0x1A3E78, 0x3E6A34, 0xA89878)
            PlanetType.ICE -> intArrayOf(0xC8D8E8, 0x8AA0B8, 0xF0F4FA)
            PlanetType.MINI_NEPTUNE -> intArrayOf(0x8AB8D8, 0x6A98C0, 0xC0E0F0)
            PlanetType.ICE_GIANT -> intArrayOf(0x5A88D8, 0x3A68B8, 0x9AC0F0)
            PlanetType.GAS_GIANT -> listOf(
                intArrayOf(0xD8B48A, 0xA07850, 0xF0E0C8), intArrayOf(0xC8A878, 0x8A6A48, 0xE8D8B8),
                intArrayOf(0xB8C8D0, 0x8898A8, 0xE0E8F0), intArrayOf(0xD89A6A, 0x985A3A, 0xF0C8A0),
            )[rng.nextInt(4)]
            PlanetType.HOT_JUPITER -> intArrayOf(0x6A2A1A, 0x3A1A10, 0xE8783A)
        }
        return intArrayOf(jitter(base[0]), jitter(base[1]), jitter(base[2]))
    }

    /** Absolute magnitude of a star from temperature and radius (solar units). */
    fun absMagFrom(teff: Double, radiusSun: Double): Double {
        val l = radiusSun * radiusSun * (teff / 5772.0).pow(4)
        return 4.83 - 2.5 * log10(l.coerceAtLeast(1e-8))
    }
}
