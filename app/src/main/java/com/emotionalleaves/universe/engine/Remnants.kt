package com.emotionalleaves.universe.engine

import com.emotionalleaves.universe.core.Rng
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import java.util.Locale
import kotlin.math.floor
import kotlin.math.pow

/**
 * The dark population between the stars, generated around the camera inside any galaxy:
 * black holes (~1 per 2,000 stars), neutron stars (~1 per 200) and rogue planets drifting
 * without a star (~1 per star; estimates from microlensing surveys).
 * Real wins: none within 150 pc (black holes, neutron stars) or 8 pc (rogue planets) of the Sun,
 * where surveys would most likely have found them.
 */
object Remnants {
    const val VERSION = 1
    private val CELL = doubleArrayOf(20.0, 3.0, 300.0)    // pc: remnants, rogue planets, clusters and nebulae
    private const val P_OPEN = 5e-7       // open star clusters per star
    private const val P_NEBULA = 6e-8     // glowing star-forming nebulae per star
    private const val RADIUS = 2
    private const val P_BH = 5e-4
    private const val P_NS = 5e-3
    private const val P_ROGUE = 1.0

    class Generated(val galaxyId: String, val bodies: List<RemnantBody>)

    private fun cell(g: GalaxyBody, tier: Int, cx: Int, cy: Int, cz: Int, emit: (Int, RemnantBody) -> Unit) {
        val size = CELL[tier]
        val c = Vec3d((cx + 0.5) * size, (cy + 0.5) * size, (cz + 0.5) * size)
        val stars = g.model.density(c.x, c.y, c.z) * size * size * size
        val mean = stars * when (tier) { 0 -> P_BH + P_NS; 1 -> P_ROGUE; else -> P_OPEN + P_NEBULA }
        val rng = Rng(Rng.hash(g.seed, 0x7E3AL, VERSION.toLong(), tier.toLong(), cx.toLong(), cy.toLong(), cz.toLong()))
        val n = rng.poisson(mean).coerceAtMost(300)
        val sun = if (g.isMilkyWay) g.toLocal(UPos.ZERO) else null
        for (i in 0 until n) {
            val local = Vec3d((cx + rng.nextDouble()) * size, (cy + rng.nextDouble()) * size, (cz + rng.nextDouble()) * size)
            val kind = when (tier) {
                1 -> Kind.ROGUE_PLANET
                2 -> if (rng.nextDouble() < P_NEBULA / (P_NEBULA + P_OPEN)) Kind.NEBULA else Kind.CLUSTER
                else -> if (rng.nextDouble() < P_BH / (P_BH + P_NS)) Kind.BLACK_HOLE else Kind.NEUTRON_STAR
            }
            val u = rng.nextDouble()
            val seed = rng.nextLong()
            // Real catalogues win: the Sun's surroundings are mapped (OpenNGC within ~1.5 kpc for clusters and nebulae).
            if (sun != null && (local - sun).length() < when (tier) { 1 -> 8.0; 2 -> 1500.0; else -> 150.0 }) continue
            emit(i, make(g, tier, cx, cy, cz, i, local, kind, u, seed))
        }
    }

    private fun make(g: GalaxyBody, tier: Int, cx: Int, cy: Int, cz: Int, i: Int, local: Vec3d, kind: Kind, u: Double, seed: Long): RemnantBody {
        val id = "rem:${g.id}:$tier:$cx,$cy,$cz:$i"
        val code = String.format(Locale.US, "%04X", (seed ushr 48).toInt() and 0xFFFF)
        val pos = g.toWorld(local)
        return when (kind) {
            Kind.BLACK_HOLE -> {
                val mSun = 5.0 + 25.0 * u.pow(2.0)
                val rs = 2953.0 * mSun
                RemnantBody(id, "BH $code-$i", kind, pos, rs, 0x5A3C88, listOf(
                    "Mass" to String.format(Locale.US, "%.1f × Sun", mSun),
                    "Event horizon" to String.format(Locale.US, "%.0f km across", rs * 2 / 1000),
                    "Glow" to "None: an isolated black hole gives off no light",
                    "Data" to "Generated (about 1 per 2,000 stars)",
                ), "A stellar black hole: the collapsed core of a giant star, so dense that not even light escapes. Alone in space it is invisible; astronomers find such objects by how they bend the light of stars behind them.")
            }
            Kind.NEUTRON_STAR -> {
                val ageMyr = 10.0.pow(u * 4)
                RemnantBody(id, "NS $code-$i", kind, pos, 11_500.0 + 1500 * u, 0xBFD8FF, listOf(
                    "Mass" to String.format(Locale.US, "%.2f × Sun", 1.25 + 0.6 * u),
                    "Size" to "about 23 km across",
                    "Age" to String.format(Locale.US, "about %,.0f million years", ageMyr),
                    "Surface" to String.format(Locale.US, "about %,.0f K", 1.0e6 / (1 + ageMyr / 1.0).pow(0.4)),
                    "Data" to "Generated (about 1 per 200 stars)",
                ), "A neutron star: what is left when a giant star explodes. More than the Sun's mass crushed into a ball the size of a city; a teaspoon of it weighs as much as a mountain.")
            }
            Kind.CLUSTER -> {
                val n = (80 + 2000 * u.pow(2.5)).toInt()
                val rPc = 1.5 + 6.0 * u
                RemnantBody(id, "OC $code-$i", kind, pos, rPc * Units.PC, 0xA8C8FF, listOf(
                    "Stars" to String.format(Locale.US, "about %,d", n),
                    "Size" to String.format(Locale.US, "%.0f light years across", rPc * 2 * 3.26),
                    "Age" to String.format(Locale.US, "about %,.0f million years", 10.0.pow(1 + 2.5 * u)),
                    "Data" to "Generated (open clusters: roughly 1 per 2 million stars)",
                ), "An open star cluster: a family of stars born together from one gas cloud, slowly drifting apart.")
            }
            Kind.NEBULA -> {
                val rPc = 8.0 + 60.0 * u
                RemnantBody(id, "HII $code-$i", kind, pos, rPc * Units.PC, 0xFF7FA8, listOf(
                    "Size" to String.format(Locale.US, "%.0f light years across", rPc * 2 * 3.26),
                    "Glow" to "Hydrogen lit up red by hot young stars inside",
                    "Data" to "Generated (star-forming nebulae along the spiral arms)",
                ), "A star-forming nebula: a giant cloud of hydrogen glowing red, lit by the hot young stars being born inside it.")
            }
            else -> {
                val re = 0.3 + 3.0 * u.pow(2.0)
                RemnantBody(id, "Rogue $code-$i", Kind.ROGUE_PLANET, pos, re * Units.R_EARTH, 0x4A3A36, listOf(
                    "Size" to String.format(Locale.US, "%.1f × Earth", re),
                    "Star" to "None: it drifts alone through the galaxy",
                    "Surface" to "Frozen and dark, warmed only from inside (about 30–60 K)",
                    "Data" to "Generated (roughly 1 per star)",
                ), "A rogue planet: a world thrown out of its star system, or one that formed alone. There may be as many of them as there are stars.")
            }
        }
    }

    fun generate(g: GalaxyBody, camLocal: Vec3d): Generated {
        val out = ArrayList<RemnantBody>()
        for (tier in 0..2) {
            val s = CELL[tier]
            val ccx = floor(camLocal.x / s).toInt(); val ccy = floor(camLocal.y / s).toInt(); val ccz = floor(camLocal.z / s).toInt()
            for (dx in -RADIUS..RADIUS) for (dy in -RADIUS..RADIUS) for (dz in -RADIUS..RADIUS) {
                cell(g, tier, ccx + dx, ccy + dy, ccz + dz) { _, b -> out.add(b) }
            }
        }
        return Generated(g.id, out)
    }

    /** rem:<galaxyId>:<tier>:<cx>,<cy>,<cz>:<i>  (galaxy ids contain one ':') */
    fun fromId(id: String, galaxyById: (String) -> GalaxyBody?): RemnantBody? {
        val p = id.removePrefix("rem:").split(":")
        if (p.size < 5) return null
        val g = galaxyById(p[0] + ":" + p[1]) ?: return null
        val tier = p[2].toIntOrNull()?.takeIf { it in 0..2 } ?: return null
        val c = p[3].split(",").mapNotNull { it.toIntOrNull() }
        val want = p[4].toIntOrNull() ?: return null
        if (c.size != 3) return null
        var found: RemnantBody? = null
        cell(g, tier, c[0], c[1], c[2]) { i, b -> if (i == want) found = b }
        return found
    }
}

/** A black hole, neutron star or rogue planet. */
class RemnantBody(
    override val id: String,
    override val name: String,
    override val kind: Kind,
    val pos: UPos,
    override val radius: Double,
    override val color: Int,
    private val extra: List<Pair<String, String>>,
    val about: String,
) : Body() {
    override val source = Source.GENERATED
    override fun position(jd: Double) = pos
    override val subtitle get() = kind.label
    override fun viewDistance() = radius * if (kind == Kind.BLACK_HOLE) 12.0 else 4.0
    override fun facts(jd: Double) = extra
}
