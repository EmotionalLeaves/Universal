package com.emotionalleaves.universe.engine

import android.content.Context
import com.emotionalleaves.universe.core.Astro
import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.core.Rng
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Real asteroids and comets (JPL Small-Body Database), on Kepler orbits around the Sun.
 * Elements are heliocentric ecliptic J2000; positions are converted to the app's equatorial frame.
 */
class SmallBodies(
    val names: Array<String>,
    /** Per body: a (AU), e, i, node, peri, M at epoch (radians), epoch JD, H (or comet M1). */
    private val el: DoubleArray,
    val diamKm: FloatArray,
    val cls: Array<String>,
    val cometFrom: Int,
) {
    val count get() = names.size
    private val byName = HashMap<String, Int>().also { m -> names.forEachIndexed { i, n -> m[n.lowercase(Locale.US)] = i } }

    fun isComet(i: Int) = i >= cometFrom
    fun a(i: Int) = el[i * 8]
    fun e(i: Int) = el[i * 8 + 1]
    fun h(i: Int) = el[i * 8 + 7]
    fun inclDeg(i: Int) = Math.toDegrees(el[i * 8 + 2])

    /** Heliocentric equatorial position in metres. */
    fun position(i: Int, jd: Double): Vec3d {
        val o = i * 8
        val a = el[o]; val e = el[o + 1]
        val n = 2 * Math.PI / periodDays(i)
        val m = el[o + 5] + n * (jd - el[o + 6])
        val ecl = Astro.orbitToPlane(a * Units.AU, e, el[o + 2], el[o + 4], el[o + 3], m)
        return Astro.eclipticToEquatorial(ecl)
    }

    fun periodDays(i: Int) = 365.25 * a(i).pow(1.5)

    fun indexOf(name: String) = byName[name.lowercase(Locale.US)]

    fun body(i: Int): SmallBody = SmallBody(this, i)

    companion object {
        fun load(context: Context): SmallBodies? = try {
            val names = ArrayList<String>(40_000)
            val el = ArrayList<Double>(320_000)
            val diam = ArrayList<Float>(40_000)
            val cls = ArrayList<String>(40_000)
            context.assets.open("smallbodies/asteroids.tsv").bufferedReader().useLines { lines ->
                for ((n, line) in lines.withIndex()) {
                    if (n == 0) continue
                    val p = line.split('\t')
                    if (p.size < 11) continue
                    val v = DoubleArray(9) { p[it + 1].toDoubleOrNull() ?: 0.0 }
                    names.add(p[0])
                    el.add(v[0]); el.add(v[1]); el.add(Math.toRadians(v[2])); el.add(Math.toRadians(v[3]))
                    el.add(Math.toRadians(v[4])); el.add(Math.toRadians(v[5])); el.add(v[6]); el.add(v[7])
                    diam.add(v[8].toFloat()); cls.add(p[10])
                }
            }
            val cometFrom = names.size
            try {
                context.assets.open("smallbodies/comets.tsv").bufferedReader().useLines { lines ->
                    for ((n, line) in lines.withIndex()) {
                        if (n == 0) continue
                        val p = line.split('\t')
                        if (p.size < 8) continue
                        val q = p[1].toDoubleOrNull() ?: continue
                        val e = p[2].toDoubleOrNull() ?: continue
                        if (e >= 0.995 || q <= 0) continue  // near-parabolic ones would need special handling
                        val a = q / (1 - e)
                        if (a > 2000) continue
                        val tp = p[6].toDoubleOrNull() ?: continue
                        names.add(p[0])
                        // Mean anomaly is 0 at perihelion time tp.
                        el.add(a); el.add(e); el.add(Math.toRadians(p[3].toDouble())); el.add(Math.toRadians(p[4].toDouble()))
                        el.add(Math.toRadians(p[5].toDouble())); el.add(0.0); el.add(tp); el.add(p[7].toDoubleOrNull() ?: 15.0)
                        diam.add(-1f); cls.add("COM")
                    }
                }
            } catch (_: Exception) {
            }
            if (names.isEmpty()) null
            else SmallBodies(names.toTypedArray(), el.toDoubleArray(), diam.toFloatArray(), cls.toTypedArray(), cometFrom)
        } catch (_: Exception) {
            null
        }

        fun className(c: String): String = when (c) {
            "MBA" -> "Main-belt asteroid"
            "IMB" -> "Inner main-belt asteroid"
            "OMB" -> "Outer main-belt asteroid"
            "APO" -> "Near-Earth asteroid (Apollo, crosses Earth's orbit)"
            "ATE" -> "Near-Earth asteroid (Aten, crosses Earth's orbit)"
            "AMO" -> "Near-Earth asteroid (Amor)"
            "IEO" -> "Asteroid inside Earth's orbit"
            "MCA" -> "Mars-crossing asteroid"
            "TJN" -> "Jupiter trojan (shares Jupiter's orbit)"
            "CEN" -> "Centaur (between Jupiter and Neptune)"
            "TNO" -> "Trans-Neptunian object"
            "AST" -> "Asteroid"
            "COM" -> "Comet"
            else -> "Asteroid"
        }
    }
}

/** One asteroid or comet. */
class SmallBody(private val set: SmallBodies, val index: Int) : Body() {
    val comet = set.isComet(index)
    override val id = (if (comet) "sbc:" else "sba:") + set.names[index]
    override val name: String = set.names[index]
    override val kind = if (comet) Kind.COMET else Kind.ASTEROID
    override val source = Source.REAL
    override val radius: Double = run {
        val d = set.diamKm[index].toDouble()
        val km = if (d > 0) d else if (comet) 5.0 else 1329.0 / sqrt(0.14) * 10.0.pow(-0.2 * set.h(index))
        km * 500.0
    }
    override val color = if (comet) 0xBFE6FF else 0xB8A898
    override fun position(jd: Double): UPos = UPos.ZERO + set.position(index, jd)
    override val subtitle get() = SmallBodies.className(set.cls[index])
    override fun viewDistance() = max(radius * 6.0, 2.0e4)

    override fun facts(jd: Double): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val d = set.diamKm[index]
        out.add("Size" to if (d > 0) String.format(Locale.US, "%,.1f km across", d) else String.format(Locale.US, "about %,.1f km across (estimated)", radius * 2 / 1000))
        val a = set.a(index); val e = set.e(index)
        out.add("Orbit" to String.format(Locale.US, "%.2f–%.2f AU from the Sun, every %s", a * (1 - e), a * (1 + e), SolarBody.period(set.periodDays(index))))
        out.add("From the Sun now" to String.format(Locale.US, "%.2f AU", set.position(index, jd).length() / Units.AU))
        out.add("Tilt of orbit" to String.format(Locale.US, "%.1f°", set.inclDeg(index)))
        if (!comet) out.add("Brightness (H)" to String.format(Locale.US, "%.1f", set.h(index)))
        out.add("Data" to "JPL Small-Body Database (real orbit)")
        return out
    }

    /** Lumpy, cratered rock (or dirty ice for comets): a little irregular sphere. */
    val shapeSeed: Long get() = Rng.hashString(id)
}

/** Pulsars, quasars and galaxy clusters: real catalogue objects drawn as points. */
class ExoticBody(
    override val id: String,
    override val name: String,
    override val kind: Kind,
    private val pos: UPos,
    override val radius: Double,
    private val extra: List<Pair<String, String>>,
    val about: String,
    override val color: Int,
) : Body() {
    override val source = Source.REAL
    /** Apparent magnitude from Earth (quasars) and Abell richness class (clusters). */
    var mag = 0.0
    var richness = 0
    var z = 0.0
    override fun position(jd: Double) = pos
    override val subtitle get() = kind.label
    override fun viewDistance() = if (radius > 0) radius * 4 else 1e12
    override fun facts(jd: Double): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out.add("Distance" to Units.formatDistance((pos - UPos.ZERO).length()))
        out.addAll(extra)
        return out
    }
}

/** Real exotic objects. Positions: pulsars in pc, quasars and clusters in Mpc (from the Sun). */
class ExoticCatalog(
    val pulsars: List<ExoticBody>,
    val quasars: List<ExoticBody>,
    val clusters: List<ExoticBody>,
) {
    private val byId = HashMap<String, ExoticBody>().also { m -> (pulsars + quasars + clusters).forEach { m[it.id] = it } }
    fun find(id: String) = byId[id]

    companion object {
        private fun rows(context: Context, path: String): List<List<String>> = try {
            context.assets.open(path).bufferedReader().useLines { s -> s.drop(1).map { it.split('\t') }.toList() }
        } catch (_: Exception) {
            emptyList()
        }

        fun load(context: Context): ExoticCatalog {
            val psr = rows(context, "exotic/pulsars.tsv").mapNotNull { p ->
                if (p.size < 5) return@mapNotNull null
                val ra = p[1].toDoubleOrNull() ?: return@mapNotNull null
                val de = p[2].toDoubleOrNull() ?: return@mapNotNull null
                val kpc = p[3].toDoubleOrNull() ?: return@mapNotNull null
                val period = p[4].toDoubleOrNull() ?: 0.0
                val spin = when {
                    period <= 0 -> "unknown"
                    period < 0.1 -> String.format(Locale.US, "%,.0f times a second", 1 / period)
                    else -> String.format(Locale.US, "once every %.2f s", period)
                }
                ExoticBody(
                    "psr:${p[0]}", p[0], Kind.PULSAR, UPos.ZERO + Vec3d.fromRaDec(ra, de) * (kpc * 1000 * Units.PC), 12_000.0,
                    listOf("Spins" to spin, "Size" to "about 24 km across, heavier than the Sun", "Data" to "ATNF Pulsar Catalogue"),
                    "A pulsar: a spinning neutron star, the crushed core of a star that exploded. It sweeps beams of radio waves across space like a lighthouse.",
                    0x9FD8FF,
                )
            }
            val qso = rows(context, "exotic/quasars.tsv").mapNotNull { p ->
                if (p.size < 6) return@mapNotNull null
                val ra = p[1].toDoubleOrNull() ?: return@mapNotNull null
                val de = p[2].toDoubleOrNull() ?: return@mapNotNull null
                val z = p[3].toDoubleOrNull() ?: return@mapNotNull null
                val mpc = p[4].toDoubleOrNull() ?: return@mapNotNull null
                val mag = p[5].toDoubleOrNull() ?: 18.0
                ExoticBody(
                    "qso:${p[0]}", p[0].ifBlank { "Quasar" }, Kind.QUASAR, UPos.ZERO + Vec3d.fromRaDec(ra, de) * (mpc * Units.MPC), 3e15,
                    listOf(
                        "Redshift" to String.format(Locale.US, "z = %.3f", z),
                        "Seen as it was" to lookback(z),
                        "Brightness from Earth" to String.format(Locale.US, "magnitude %.1f", mag),
                        "Data" to "Million Quasars catalogue (Milliquas)",
                    ),
                    "A quasar: the blazing centre of a far-away galaxy, where gas spirals into a supermassive black hole and shines brighter than all the galaxy's stars together.",
                    0xC8D8FF,
                ).also { it.mag = mag; it.z = z }
            }
            val gcl = rows(context, "exotic/clusters.tsv").mapNotNull { p ->
                if (p.size < 7) return@mapNotNull null
                val ra = p[1].toDoubleOrNull() ?: return@mapNotNull null
                val de = p[2].toDoubleOrNull() ?: return@mapNotNull null
                val z = p[3].toDoubleOrNull() ?: return@mapNotNull null
                val mpc = p[4].toDoubleOrNull() ?: return@mapNotNull null
                val rich = p[5].toIntOrNull() ?: 1
                ExoticBody(
                    "gcl:${p[0]}", p[0], Kind.GALAXY_CLUSTER, UPos.ZERO + Vec3d.fromRaDec(ra, de) * (mpc * Units.MPC), 1.5 * Units.MPC,
                    listOf(
                        "Galaxies" to when (rich) { 0 -> "30–49 bright ones"; 1 -> "50–79 bright ones"; 2 -> "80–129 bright ones"; 3 -> "130–199 bright ones"; else -> "200 or more bright ones" } + " (plus many fainter)",
                        "Redshift" to String.format(Locale.US, "z = %.3f%s", z, if (p[6] == "G") " (estimated)" else ""),
                        "Seen as it was" to lookback(z),
                        "Data" to "Abell catalogue of rich clusters",
                    ),
                    "A galaxy cluster: hundreds to thousands of galaxies held together by gravity, bathed in hot gas. Fly in to see its member galaxies.",
                    0xFFD8A8,
                ).also { it.richness = rich; it.z = z }
            }
            return ExoticCatalog(psr, qso, gcl)
        }

        /** Light travel time for redshift z (flat LCDM, H0 = 70, Omega_m = 0.3). */
        fun lookback(z: Double): String {
            val n = 200
            var s = 0.0
            for (k in 0 until n) {
                val zz = (k + 0.5) * z / n
                s += 1.0 / ((1 + zz) * sqrt(0.3 * (1 + zz).pow(3) + 0.7))
            }
            val gyr = 977.8 / 70.0 * s * z / n
            return String.format(Locale.US, "%.1f billion years ago", gyr)
        }
    }
}

/** Every known moon of the planets (JPL mean elements), added beside the hand-made major moons. */
object CatalogMoons {
    /** Adds moons from planets/moons.tsv; major moons already present get the real orbit instead. */
    fun addTo(context: Context, all: MutableList<SolarBody>) {
        val rows = try {
            context.assets.open("planets/moons.tsv").bufferedReader().useLines { s -> s.drop(1).map { it.split('\t') }.toList() }
        } catch (_: Exception) {
            return
        }
        val planets = all.filter { it.parent?.id == "sol:sun" }.associateBy { it.name.lowercase(Locale.US) }
        val existing = all.associateBy { it.name.lowercase(Locale.US) }
        for (p in rows) {
            if (p.size < 13) continue
            val planet = planets[p[0].lowercase(Locale.US)] ?: continue
            val name = p[1].trim()
            if (name.isEmpty()) continue
            val aKm = p[2].toDoubleOrNull() ?: continue
            val e = (p[3].toDoubleOrNull() ?: 0.0).coerceIn(0.0, 0.95)
            val w = Math.toRadians(p[4].toDoubleOrNull() ?: 0.0)
            val m0 = Math.toRadians(p[5].toDoubleOrNull() ?: 0.0)
            val inc = Math.toRadians(p[6].toDoubleOrNull() ?: 0.0)
            val node = Math.toRadians(p[7].toDoubleOrNull() ?: 0.0)
            val pd = abs(p[8].toDoubleOrNull() ?: 0.0).takeIf { it > 0 } ?: continue
            val retro = (p[6].toDoubleOrNull() ?: 0.0) > 90
            val poleRa = p[9].toDoubleOrNull()?.takeIf { it > -900 } ?: planet.poleRa
            val poleDec = p[10].toDoubleOrNull()?.takeIf { it > -900 } ?: planet.poleDec
            val epoch = parseEpoch(p[11])
            val rKm = (p[12].toDoubleOrNull() ?: 0.0).takeIf { it > 0 } ?: 2.0
            // Laplace-plane frame: Z = plane pole, X = its ascending node on the equator.
            val z = Vec3d.fromRaDec(poleRa, poleDec)
            var x = Vec3d.Z.cross(z)
            x = if (x.lengthSq() < 1e-12) Vec3d.X else x.normalized()
            val frame = Quatd.fromBasis(x, z.cross(x).normalized(), z)
            val a = aKm * 1000
            val fn = { jd: Double ->
                val m = m0 + 2 * Math.PI * (jd - epoch) / pd
                frame.rotate(Astro.orbitToPlane(a, e, inc, w, node, m))
            }
            val known = existing[name.lowercase(Locale.US)]
            if (known != null && known.parent == planet) {
                known.relOverride = fn
                continue
            }
            val id = "sol:moon:" + name.lowercase(Locale.US).replace(' ', '_')
            val grey = 0x7A7268 + ((Rng.hashString(id) and 0x0F0F0F).toInt())
            val m = SolarBody(
                id, name, Kind.MOON, rKm * 1000, planet, fn, planet.poleRa, planet.poleDec, 0.0, 0.0, true,
                Look(style = 0, colorA = grey, colorB = grey - 0x101010, colorC = grey + 0x080808),
                5.0 - 5 * log10(max(rKm, 0.5) / 100), pd, a, 0.0,
                listOf(
                    "Orbit shape" to String.format(Locale.US, "eccentricity %.3f, tilt %.1f°%s", e, Math.toDegrees(inc), if (retro) " (goes backwards)" else ""),
                    "Position" to "JPL mean orbital elements",
                ),
                grey,
            )
            planet.children.add(m)
            all.add(m)
        }
    }

    /** JPL epochs look like "2000 Jan. 1.50 TT" or a Julian date; unknown formats use J2000. */
    private fun parseEpoch(s: String): Double {
        s.trim().toDoubleOrNull()?.let { if (it > 2_000_000) return it }
        val m = Regex("""(\d{4})\s+([A-Za-z]{3})[a-z.]*\s+([\d.]+)""").find(s) ?: return Astro.J2000
        val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        val mon = months.indexOf(m.groupValues[2].lowercase(Locale.US)) + 1
        if (mon == 0) return Astro.J2000
        val y = m.groupValues[1].toInt()
        val day = m.groupValues[3].toDouble()
        // Julian date from a calendar date (Meeus).
        var yy = y; var mm = mon
        if (mm <= 2) { yy -= 1; mm += 12 }
        val a = yy / 100
        val b = 2 - a + a / 4
        return (365.25 * (yy + 4716)).toInt() + (30.6001 * (mm + 1)).toInt() + day + b - 1524.5
    }
}
