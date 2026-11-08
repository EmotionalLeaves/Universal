package com.emotionalleaves.universe.engine

import com.emotionalleaves.universe.core.TsvRow

import android.content.Context
import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.core.Rng
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Loads galaxies (Cosmicflows-4 + OpenNGC) and turns each row into an enterable GalaxyBody. */
object GalaxyCatalog {
    /** Friendly names for well-known galaxies that catalogues list by number. */
    private val NAMES = mapOf(
        17223 to "Large Magellanic Cloud", 3085 to "Small Magellanic Cloud", 2557 to "Andromeda Galaxy",
        5818 to "Triangulum Galaxy", 2555 to "M32", 2429 to "M110", 28630 to "Bode's Galaxy",
        28655 to "Cigar Galaxy", 47404 to "Whirlpool Galaxy", 42407 to "Sombrero Galaxy",
        46957 to "Centaurus A", 41361 to "Messier 87", 50063 to "Pinwheel Galaxy", 3589 to "Sculptor Galaxy",
        48082 to "Southern Pinwheel", 43234 to "Black Eye Galaxy", 10266 to "Fornax A",
    )

    /** Local Group members that may be missing from the distance catalogue. */
    private class Extra(val pgc: Int, val name: String, val ra: Double, val dec: Double, val mpc: Double,
                        val absMag: Double, val rKpc: Double, val hubble: String)

    private val EXTRAS = listOf(
        Extra(17223, "Large Magellanic Cloud", 80.894, -69.756, 0.0496, -18.1, 7.0, "SBm"),
        Extra(3085, "Small Magellanic Cloud", 13.187, -72.829, 0.062, -16.8, 3.5, "SBm"),
        Extra(2557, "Andromeda Galaxy", 10.6847, 41.2690, 0.765, -21.5, 33.0, "SAb"),
        Extra(5818, "Triangulum Galaxy", 23.4621, 30.6599, 0.86, -18.9, 9.0, "SAcd"),
    )

    fun load(context: Context): List<GalaxyBody> = try {
        context.assets.open("galaxies/galaxies.tsv").bufferedReader().useLines { parse(it) }
    } catch (_: Exception) {
        parse(emptySequence())
    }

    /** "NGC 224" / "IC 342" style designations of catalogue galaxies, to their ids (for search). */
    val designations = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** "NGC0224" -> "NGC 224". */
    fun designation(raw: String): String? {
        val letters = raw.takeWhile { it.isLetter() }
        val digits = raw.drop(letters.length).trimStart('0').trim()
        if (letters.isEmpty() || digits.isEmpty()) return null
        return "$letters $digits"
    }

    fun parse(lines: Sequence<String>): List<GalaxyBody> {
        val out = ArrayList<GalaxyBody>(64_000)
        val seen = HashSet<Int>()
        run {
            run {
                val p = TsvRow()
                for (l in lines.drop(1)) {
                    p.set(l)
                    if (p.size < 12) continue
                    val pgc = p.int(1)
                    if (pgc != null && !seen.add(pgc)) continue
                    val g = build(
                        name = pgc?.let { NAMES[it] } ?: p.str(0), pgc = pgc, ra = p.num(2) ?: continue,
                        dec = p.num(3) ?: continue, mpc = p.num(4) ?: continue,
                        absMag = p.num(5), rKpc = p.num(6), hubble = p.str(7),
                        pa = p.num(8), incl = p.num(9), distSrc = p.str(10), ngc = p.str(11),
                    ) ?: continue
                    out.add(g)
                    val ngc = p.str(11)
                    if (ngc.isNotEmpty()) designation(ngc)?.let { designations[it] = g.id }
                }
            }
        }
        for (e in EXTRAS) {
            if (e.pgc in seen) continue
            build(e.name, e.pgc, e.ra, e.dec, e.mpc, e.absMag, e.rKpc, e.hubble, null, null, "R", "")?.let { out.add(it) }
        }
        return out
    }

    private fun build(
        name: String, pgc: Int?, ra: Double, dec: Double, mpc: Double, absMag: Double?, rKpc: Double?,
        hubble: String, pa: Double?, incl: Double?, distSrc: String, ngc: String,
    ): GalaxyBody? {
        if (mpc <= 0.03) return null // inside the Milky Way: not a galaxy distance
        val seed = Rng.hash(pgc?.toLong() ?: Rng.hashString(name + ra))
        val rng = Rng(seed)
        val generatedLooks = ArrayList<String>()
        val mag = absMag ?: (-19.6 + rng.gaussian() * 1.1).also { generatedLooks.add("brightness") }
        val type = GalaxyType.fromHubble(hubble, seed)
        if (hubble.isBlank()) generatedLooks.add("type")
        val radius = (rKpc ?: (11.0 * 10.0.pow(-0.2 * (mag + 21.0)) * (0.7 + rng.nextDouble() * 0.6)).also { generatedLooks.add("size") })
            .coerceIn(0.4, 90.0)
        val dir = Vec3d.fromRaDec(ra, dec)
        val center = UPos.ZERO + dir * (mpc * Units.MPC)
        // Orientation from position angle and inclination when known, otherwise random.
        val north = (Vec3d.Z - dir * dir.z).normalized()
        val east = Vec3d.Z.cross(dir).normalized()
        val paR = Math.toRadians(pa ?: (rng.nextDouble() * 180).also { if (incl == null) generatedLooks.add("tilt") })
        val major = north * cos(paR) + east * sin(paR)
        val inc = Math.toRadians(incl ?: (rng.nextDouble() * 90))
        val minorSky = dir.cross(major).normalized()
        val normal = (dir * cos(inc) + minorSky * sin(inc)).normalized()
        val x = (major - normal * major.dot(normal)).normalized()
        val frame = Quatd.fromBasis(x, normal.cross(x).normalized(), normal)
        val model = GalaxyModel.forGalaxy(type, radius, seed)
        val display = when {
            name.isNotBlank() -> name
            ngc.isNotBlank() -> ngc
            else -> "PGC $pgc"
        }
        // Facts are only needed when a card opens: built then, not for all 62,000 galaxies at startup.
        val facts = LazyFacts {
            val facts = ArrayList<Pair<String, String>>()
            if (hubble.isNotBlank()) facts.add("Hubble type" to hubble)
            val ids = listOfNotNull(ngc.takeIf { it.isNotBlank() }, pgc?.let { "PGC $it" }).filter { it != display }
            if (ids.isNotEmpty()) facts.add("Catalogue" to ids.joinToString(", "))
            facts.add("Distance source" to when (distSrc) {
                "R" -> "Measured (Cosmicflows-4)"
                "Z" -> "From redshift (Hubble's law)"
                else -> distSrc
            })
            if (generatedLooks.isNotEmpty()) facts.add("Generated" to generatedLooks.joinToString(", ") + "; stars inside are generated")
            else facts.add("Inside" to "Stars and planets are generated")
            facts
        }
        return GalaxyBody(
            "gal:" + (pgc?.let { "pgc$it" } ?: ngc.ifBlank { "x${seed and 0xFFFFFF}" }), display, center, frame, model,
            radius * 1000, mpc * 3.2616, Source.REAL, facts, seed, mag,
        )
    }
}

/** A list built on first use (card facts for catalogue objects). */
class LazyFacts(build: () -> List<Pair<String, String>>) : AbstractList<Pair<String, String>>() {
    private val v by lazy(build)
    override val size get() = v.size
    override fun get(index: Int) = v[index]
}
