package com.emotionalleaves.universe.engine

import com.emotionalleaves.universe.core.Astro
import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.core.Rng
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import java.util.Locale
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Every rock: belts and comet clouds filled in around the camera down to 1-metre boulders.
 *
 * Bodies follow the usual size law (number larger than D ∝ D^-2.5). Each size range lives in its
 * own grid of cells, sized so a cell holds about a dozen bodies of that range, and only cells
 * around the camera are generated: big rocks appear from far away, pebbles only up close.
 * Real catalogues win: in our Solar System only bodies smaller than the catalogue limit are made.
 */
object RockField {
    const val VERSION = 1

    /** A belt, ring or cloud of bodies around a star. Positions in AU from [center]. */
    class Zone(
        val key: String, val index: Int, val starName: String, val center: UPos, val frame: Quatd?,
        val innerAu: Double, val outerAu: Double, val halfThickness: Double,
        val n1km: Double, val icy: Boolean, val maxKm: Double, val label: String,
    ) {
        val volumeAu3: Double = if (frame == null) 4.0 / 3 * PI * (outerAu.pow(3) - innerAu.pow(3))
        else PI * (outerAu * outerAu - innerAu * innerAu) * 2 * halfThickness * (innerAu + outerAu) / 2

        /** Inside the zone? [p] in AU from the centre (world orientation). */
        fun contains(p: Vec3d): Boolean {
            if (frame == null) { val r = p.length(); return r in innerAu..outerAu }
            val l = frame.conjugate().rotate(p)
            val r = sqrt(l.x * l.x + l.y * l.y)
            return r in innerAu..outerAu && kotlin.math.abs(l.z) < halfThickness * r
        }

        /** Bodies per AU³ with diameters in [lo, hi) km. */
        fun density(lo: Double, hi: Double) = n1km * (lo.pow(-2.5) - hi.pow(-2.5)) / volumeAu3

        /** Total bodies down to 1 m that this zone holds (below the catalogue limit). */
        fun total() = n1km * (0.001.pow(-2.5) - maxKm.pow(-2.5))
    }

    /** Size ranges (km), largest first: each is a level of detail. */
    private val TIERS = doubleArrayOf(1000.0, 100.0, 10.0, 1.0, 0.1, 0.01, 0.001)
    private const val PER_CELL = 12.0
    private const val RADIUS = 2

    // ---------------------------------------------------------------- zones

    /** Our Solar System: main belt (below the ~5 km catalogue limit), Kuiper belt, Oort cloud. */
    val solarZones: List<Zone> by lazy {
        val ecl = Quatd.fromBasis(Vec3d.X, Astro.eclipticToEquatorial(Vec3d.Y), Astro.eclipticToEquatorial(Vec3d.Z))
        listOf(
            Zone("sun", 0, "Sun", UPos.ZERO, ecl, 2.1, 3.3, 0.12, 1.1e6, false, 5.0, "main asteroid belt"),
            Zone("sun", 1, "Sun", UPos.ZERO, ecl, 30.0, 50.0, 0.18, 3e9, true, 100.0, "Kuiper belt"),
            Zone("sun", 2, "Sun", UPos.ZERO, null, 2000.0, 100_000.0, 1.0, 1e12, true, 50.0, "Oort cloud"),
        )
    }

    /** Zones of another star: its generated belts plus a comet cloud. */
    fun zonesFor(sys: StarSystem): List<Zone> {
        val star = sys.star
        val m = sqrt(star.luminosity.coerceIn(1e-4, 1e4)).pow(0.5)
        val out = ArrayList<Zone>()
        for (b in Belts.forSystem(sys)) {
            out.add(Zone(star.id, out.size, star.name, star.pos, b.frame, b.innerAu, b.outerAu, if (b.icy) 0.18 else 0.12,
                if (b.icy) 3e9 else 1e6, b.icy, 1000.0, b.label))
        }
        out.add(Zone(star.id, out.size, star.name, star.pos, null, 2000.0 * m, 80_000.0 * m, 1.0, 5e11, true, 100.0, "comet cloud"))
        return out
    }

    // ---------------------------------------------------------------- generation

    private fun cellSizeAu(z: Zone, tier: Int): Double {
        val hi = if (tier == 0) z.maxKm.coerceAtLeast(TIERS[0]) * 10 else TIERS[tier - 1]
        val lo = TIERS[tier]
        if (lo >= z.maxKm) return 0.0
        val n = z.density(lo, min(hi, z.maxKm))
        return if (n <= 0) 0.0 else (PER_CELL / n).pow(1.0 / 3)
    }

    private fun cell(z: Zone, tier: Int, cx: Long, cy: Long, cz: Long, emit: (Int, RockBody) -> Unit) {
        val s = cellSizeAu(z, tier)
        if (s <= 0) return
        val c = Vec3d((cx + 0.5) * s, (cy + 0.5) * s, (cz + 0.5) * s)
        if (!z.contains(c)) return
        val lo = TIERS[tier]
        val hi = min(if (tier == 0) lo * 10 else TIERS[tier - 1], z.maxKm)
        val rng = Rng(Rng.hash(Rng.hashString(z.key), z.index.toLong(), tier.toLong(), cx, cy, cz, VERSION.toLong()))
        val n = rng.poisson(PER_CELL).coerceAtMost(60)
        for (i in 0 until n) {
            val p = Vec3d((cx + rng.nextDouble()) * s, (cy + rng.nextDouble()) * s, (cz + rng.nextDouble()) * s)
            // Size from the power law between lo and hi.
            val u = rng.nextDouble()
            val a = lo.pow(-2.5); val b = hi.pow(-2.5)
            val km = (a - u * (a - b)).pow(-1 / 2.5)
            val seed = rng.nextLong()
            if (!z.contains(p)) continue
            emit(i, RockBody(z, tier, cx, cy, cz, i, p, km, seed))
        }
    }

    class Generated(val zones: List<Zone>, val bodies: List<RockBody>, val anchor: UPos)

    /** Bodies near [cam] in every zone you are inside (or close to). */
    fun generate(zones: List<Zone>, cam: UPos): Generated {
        val out = ArrayList<RockBody>()
        for (z in zones) {
            val p = (cam - z.center) / Units.AU
            val r = p.length()
            if (r > z.outerAu * 1.3 || r < z.innerAu * 0.7) continue
            for (tier in TIERS.indices) {
                val s = cellSizeAu(z, tier)
                if (s <= 0) continue
                val ccx = floor(p.x / s).toLong(); val ccy = floor(p.y / s).toLong(); val ccz = floor(p.z / s).toLong()
                for (dx in -RADIUS..RADIUS) for (dy in -RADIUS..RADIUS) for (dz in -RADIUS..RADIUS) {
                    cell(z, tier, ccx + dx, ccy + dy, ccz + dz) { _, b -> out.add(b) }
                }
                if (out.size > 12_000) break
            }
        }
        return Generated(zones, out, cam)
    }

    /** Smallest cell in use near [cam] (AU): how far you can move before regenerating. */
    fun finestCellAu(zones: List<Zone>, cam: UPos): Double {
        var best = Double.MAX_VALUE
        for (z in zones) {
            val r = ((cam - z.center) / Units.AU).length()
            if (r > z.outerAu * 1.3 || r < z.innerAu * 0.7) continue
            for (t in TIERS.indices) { val s = cellSizeAu(z, t); if (s > 0) best = min(best, s) }
        }
        return best
    }

    /** id: rock|<zoneKey>|<zone>|<tier>|<cx>,<cy>,<cz>|<i> */
    fun fromId(id: String, zonesForKey: (String) -> List<Zone>?): RockBody? {
        val p = id.split("|")
        if (p.size != 6 || p[0] != "rock") return null
        val z = zonesForKey(p[1])?.getOrNull(p[2].toIntOrNull() ?: return null) ?: return null
        val tier = p[3].toIntOrNull()?.takeIf { it in TIERS.indices } ?: return null
        val c = p[4].split(",").mapNotNull { it.toLongOrNull() }
        if (c.size != 3) return null
        val want = p[5].toIntOrNull() ?: return null
        var found: RockBody? = null
        cell(z, tier, c[0], c[1], c[2]) { i, b -> if (i == want) found = b }
        return found
    }

    /** Everything the generators can make, for the census (see the What's in the universe screen). */
    fun rocksPerStar(): Double {
        val belt = 0.55 * 1e6 * 0.001.pow(-2.5)
        val kuiper = 0.6 * 3e9 * 0.001.pow(-2.5)
        val cloud = 5e11 * 0.001.pow(-2.5)
        return belt + kuiper + cloud
    }
}

/** A generated asteroid, boulder or comet nucleus in a belt or cloud. */
class RockBody(
    val zone: RockField.Zone, tier: Int, cx: Long, cy: Long, cz: Long, i: Int, private val localAu: Vec3d, val km: Double, seed: Long,
) : Body() {
    override val id = "rock|${zone.key}|${zone.index}|$tier|$cx,$cy,$cz|$i"
    override val name: String = String.format(
        Locale.US, "%s %04X-%d",
        when { km < 0.01 -> "Boulder"; km < 1 -> "Rock"; zone.icy -> "Iceball"; else -> "Asteroid" },
        (seed ushr 48).toInt() and 0xFFFF, i,
    )
    override val kind = if (zone.label == "Oort cloud" || zone.label == "comet cloud") Kind.COMET else Kind.ASTEROID
    override val source = Source.GENERATED
    override val radius = km * 500.0
    override val color = if (zone.icy) 0xC8D4F0 else 0xC4B49C
    val pos: UPos = zone.center + localAu * Units.AU
    val distanceAu get() = localAu.length()
    override fun position(jd: Double) = pos
    override val subtitle get() = "${if (km < 0.01) "Boulder" else if (zone.icy) "Icy body" else "Rock"} in ${if (zone.key == "sun") "the Sun" else zone.starName}'s ${zone.label}"
    override fun viewDistance() = max(radius * 6.0, 30.0)
    override fun facts(jd: Double) = listOf(
        "Size" to if (km >= 1) String.format(Locale.US, "%,.1f km across", km) else String.format(Locale.US, "%,.0f m across", km * 1000),
        "From ${zone.starName}" to String.format(Locale.US, "%,.2f AU", distanceAu),
        "Where" to "${zone.label.replaceFirstChar { it.uppercase() }}, ${String.format(Locale.US, "%,.0f–%,.0f AU", zone.innerAu, zone.outerAu)}",
        "Neighbours" to String.format(Locale.US, "about %s like it in this %s", Units.bigCount(zone.total()), zone.label),
        "Data" to "Generated (size law N ∝ D^-2.5, down to 1 m)",
    )
}

/** How many things the app can generate and let you visit (shown in the census and on the universe card). */
object Census {
    const val STARS_OBSERVABLE = 2e23

    /** Per star: the star, ~2 planets, their moons, remnants, a rogue planet, and every rock down to 1 m. */
    fun perStar() = 1 + 2.0 + 2.0 * 0.11 + 0.0055 + 1.0 + RockField.rocksPerStar()

    fun observable() = STARS_OBSERVABLE * perStar()

    fun withBeyond(): Double { val f = CosmicWeb.BEYOND_FACTOR; return observable() * f * f * f }

    fun total(beyond: Boolean) = if (beyond) withBeyond() else observable()
}
