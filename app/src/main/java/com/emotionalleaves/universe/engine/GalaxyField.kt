package com.emotionalleaves.universe.engine

import com.emotionalleaves.universe.core.Noise3
import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.core.Rng
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import java.util.Locale
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Galaxies generated around the camera, anywhere in the universe, so there is always more to find
 * than the catalogues hold (the real universe has about 2 trillion galaxies).
 *
 * Two tiers of cubic cells, like the star field:
 *  - tier 0: normal galaxies (absolute magnitude -23.5 to -17), 4 Mpc cells, 7×7×7 around you
 *  - tier 1: dwarf galaxies (-16 to -10), 1 Mpc cells, 7×7×7 around you
 * Density follows a seeded filament field, so galaxies line up in walls and threads with voids
 * between. Real catalogues win: nothing is generated that would look brighter from Earth than the
 * catalogues are complete to, and nothing inside the Local Group.
 */
class FieldGalaxies(
    /** Point data relative to [anchor], in Mpc: x, y, z, r, g, b, absMag (7 floats each). */
    val anchor: UPos,
    val data: FloatArray,
    val radiusPc: FloatArray,
    val tiers: ByteArray,
    /** Cell coordinates (3 ints per galaxy): no packing, so ids stay valid however far out you go. */
    val cells: IntArray,
    val index: ShortArray,
) {
    val count get() = tiers.size

    fun id(i: Int): String = GalaxyField.idFor(tiers[i].toInt(), cells[i * 3], cells[i * 3 + 1], cells[i * 3 + 2], index[i].toInt())

    fun position(i: Int): UPos = anchor + Vec3d(data[i * 7].toDouble(), data[i * 7 + 1].toDouble(), data[i * 7 + 2].toDouble()) * Units.MPC
}

object GalaxyField {
    const val VERSION = 1
    private const val SEED = 0x6A1AF1E1DL
    val CELL = doubleArrayOf(4.0, 1.0)            // Mpc
    private val MEAN = doubleArrayOf(0.05, 0.5)   // galaxies per Mpc^3 at average density
    private const val RADIUS = 3
    /** Apparent magnitude (from Earth) the real catalogues are complete to; brighter ones are real. */
    @Volatile var catalogLimit = 14.0
    private const val LOCAL_GROUP_MPC = 1.5

    private val noise = Noise3(SEED)

    /** Real galaxy clusters (Abell). Each gets generated member galaxies (tier 2). */
    @Volatile var clusters: List<ExoticBody> = emptyList()

    /** Member galaxies of real cluster [ci]: concentrated towards the centre, mostly ellipticals. */
    private fun clusterMembers(ci: Int, emit: (Int, Raw) -> Unit) {
        val c = clusters.getOrNull(ci) ?: return
        val center = (c.position(0.0) - UPos.ZERO) / Units.MPC
        val rng = Rng(Rng.hash(SEED, VERSION.toLong(), 2L, ci.toLong()))
        val n = (60 + 70 * c.richness + rng.nextInt(40)).coerceAtMost(420)
        val limit = catalogLimit - 3   // the catalogues hold far fewer cluster galaxies than the field
        for (i in 0 until n) {
            val u = rng.nextDouble()
            val r = if (i == 0) 0.0 else (0.25 * kotlin.math.tan(u * 1.35)).coerceAtMost(2.5)
            val p = center + rng.unitVector() * r
            val m = if (i == 0) -23.2 + rng.nextDouble() * 0.6 else max(-22.6, -17.5 + 1.3 * ln(max(rng.nextDouble(), 1e-9)))
            val seed = rng.nextLong()
            val app = m + 5 * log10(p.length() * 1e6) - 5
            if (app < limit) continue
            emit(i, Raw(p, m, seed))
        }
    }

    /** Relative galaxy density from the filament field (average about 1). */
    fun webDensity(xMpc: Double, yMpc: Double, zMpc: Double): Double {
        val s = 1.0 / 30.0
        val w = noise.ridged(xMpc * s, yMpc * s, zMpc * s, 3)
        return 0.05 + 4.0 * w * w * w
    }

    fun idFor(tier: Int, cx: Int, cy: Int, cz: Int, i: Int): String = "gal:f${tier}x${cx}y${cy}z${cz}n$i"

    private class Raw(val pMpc: Vec3d, val absMag: Double, val seed: Long)

    /** All galaxies in one cell. Deterministic. Positions are relative to the Sun, in Mpc. */
    private fun cell(tier: Int, cx: Int, cy: Int, cz: Int, emit: (Int, Raw) -> Unit) {
        val size = CELL[tier]
        val c = Vec3d((cx + 0.5) * size, (cy + 0.5) * size, (cz + 0.5) * size)
        val mean = MEAN[tier] * size * size * size * webDensity(c.x, c.y, c.z)
        val rng = Rng(Rng.hash(SEED, VERSION.toLong(), tier.toLong(), cx.toLong(), cy.toLong(), cz.toLong()))
        val n = rng.poisson(mean).coerceAtMost(400)
        for (i in 0 until n) {
            val p = Vec3d((cx + rng.nextDouble()) * size, (cy + rng.nextDouble()) * size, (cz + rng.nextDouble()) * size)
            val u = rng.nextDouble()
            // Many more faint galaxies than bright ones (a rough Schechter function).
            val m = if (tier == 0) max(-23.5, -17.0 + 1.25 * ln(max(u, 1e-9))) else -16.0 + 6.0 * u.pow(0.6)
            val seed = rng.nextLong()
            val d = p.length()
            if (d < LOCAL_GROUP_MPC) continue
            val app = m + 5 * log10(d * 1e6) - 5
            if (app < catalogLimit) continue
            emit(i, Raw(p, m, seed))
        }
    }

    /** Generates both tiers around [cam]. */
    fun generate(cam: UPos): FieldGalaxies {
        val camMpc = (cam - UPos.ZERO) / Units.MPC
        val big = CELL[0]
        val anchorMpc = Vec3d(floor(camMpc.x / big) * big, floor(camMpc.y / big) * big, floor(camMpc.z / big) * big)
        val anchor = UPos.ZERO + anchorMpc * Units.MPC
        val data = ArrayList<Float>(8000)
        val rad = ArrayList<Float>(1200)
        val tiers = ArrayList<Byte>(1200)
        val cells = ArrayList<Int>(3600)
        val idx = ArrayList<Short>(1200)
        for (tier in 0..1) {
            val size = CELL[tier]
            val ccx = floor(camMpc.x / size).toInt()
            val ccy = floor(camMpc.y / size).toInt()
            val ccz = floor(camMpc.z / size).toInt()
            for (dx in -RADIUS..RADIUS) for (dy in -RADIUS..RADIUS) for (dz in -RADIUS..RADIUS) {
                val cx = ccx + dx; val cy = ccy + dy; val cz = ccz + dz
                cell(tier, cx, cy, cz) { i, r ->
                    val rel = r.pMpc - anchorMpc
                    val t = typeFor(tier, r.seed)
                    val col = when (t) {
                        GalaxyType.ELLIPTICAL, GalaxyType.LENTICULAR -> floatArrayOf(1f, 0.86f, 0.68f)
                        GalaxyType.IRREGULAR, GalaxyType.DWARF -> floatArrayOf(0.72f, 0.82f, 1f)
                        else -> floatArrayOf(0.88f, 0.9f, 1f)
                    }
                    data.add(rel.x.toFloat()); data.add(rel.y.toFloat()); data.add(rel.z.toFloat())
                    data.add(col[0]); data.add(col[1]); data.add(col[2]); data.add(r.absMag.toFloat())
                    rad.add(radiusKpc(r.absMag, r.seed).toFloat() * 1000f)
                    tiers.add(tier.toByte()); cells.add(cx); cells.add(cy); cells.add(cz); idx.add(i.toShort())
                }
            }
        }
        // Members of real clusters within reach.
        for ((ci, c) in clusters.withIndex()) {
            val cp = (c.position(0.0) - UPos.ZERO) / Units.MPC
            if ((cp - camMpc).length() > 30.0) continue
            clusterMembers(ci) { i, r ->
                val rel = r.pMpc - anchorMpc
                val col = if (typeFor(2, r.seed) == GalaxyType.ELLIPTICAL || typeFor(2, r.seed) == GalaxyType.LENTICULAR) floatArrayOf(1f, 0.86f, 0.68f) else floatArrayOf(0.88f, 0.9f, 1f)
                data.add(rel.x.toFloat()); data.add(rel.y.toFloat()); data.add(rel.z.toFloat())
                data.add(col[0]); data.add(col[1]); data.add(col[2]); data.add(r.absMag.toFloat())
                rad.add(radiusKpc(r.absMag, r.seed).toFloat() * 1000f)
                tiers.add(2.toByte()); cells.add(ci); cells.add(0); cells.add(0); idx.add(i.toShort())
            }
        }
        return FieldGalaxies(anchor, data.toFloatArray(), rad.toFloatArray(), tiers.toByteArray(), cells.toIntArray(), idx.toShortArray())
    }

    private fun typeFor(tier: Int, seed: Long): GalaxyType {
        if (tier == 1) return if (Rng(seed).nextInt(3) == 0) GalaxyType.IRREGULAR else GalaxyType.DWARF
        if (tier == 2) return when (Rng(seed).nextInt(10)) { in 0..4 -> GalaxyType.ELLIPTICAL; in 5..6 -> GalaxyType.LENTICULAR; 7 -> GalaxyType.SPIRAL; 8 -> GalaxyType.BARRED; else -> GalaxyType.DWARF }
        return GalaxyType.fromHubble("", seed)
    }

    private fun radiusKpc(absMag: Double, seed: Long): Double {
        val r = Rng(seed xor 0x5EED)
        return (11.0 * 10.0.pow(-0.2 * (absMag + 21.0)) * (0.7 + r.nextDouble() * 0.6)).coerceIn(0.3, 60.0)
    }

    private fun body(tier: Int, cx: Int, cy: Int, cz: Int, i: Int, raw: Raw): GalaxyBody {
        val seed = raw.seed
        val rng = Rng(seed)
        val type = typeFor(tier, seed)
        val radiusKpc = radiusKpc(raw.absMag, seed)
        val n = rng.unitVector()
        val x = Vec3d.perpendicular(n)
        val frame = Quatd.fromBasis(x, n.cross(x).normalized(), n)
        val prefix = when (tier) { 0 -> "GF"; 1 -> "DW"; else -> clusters.getOrNull(cx)?.name?.replace("Abell ", "A") ?: "CL" }
        val name = String.format(Locale.US, "%s-%04X-%d", prefix, (seed ushr 48).toInt() and 0xFFFF, i)
        val stars = 10.0.pow(-0.4 * (raw.absMag + 21.0)) * 1.0e11
        return GalaxyBody(
            idFor(tier, cx, cy, cz, i), name, UPos.ZERO + raw.pMpc * Units.MPC, frame,
            GalaxyModel.forGalaxy(type, radiusKpc, seed), radiusKpc * 1000, raw.pMpc.length() * 3.2616, Source.GENERATED,
            listOf(
                "Stars" to "about ${Units.bigCount(stars)}",
                "Data" to if (tier == 2) "Generated member of the real cluster ${clusters.getOrNull(cx)?.name ?: ""}" else "Generated where the catalogues run out (galaxy field v$VERSION)",
            ),
            seed, raw.absMag,
        )
    }

    /** Rebuilds a field galaxy from its id: gal:f<tier>x<cx>y<cy>z<cz>n<i>. */
    fun fromId(id: String): GalaxyBody? {
        val m = Regex("""gal:f(\d)x(-?\d+)y(-?\d+)z(-?\d+)n(\d+)""").matchEntire(id) ?: return null
        val tier = m.groupValues[1].toInt()
        if (tier !in 0..2) return null
        val cx = m.groupValues[2].toInt(); val cy = m.groupValues[3].toInt(); val cz = m.groupValues[4].toInt()
        val want = m.groupValues[5].toInt()
        var out: GalaxyBody? = null
        if (tier == 2) clusterMembers(cx) { i, r -> if (i == want) out = body(tier, cx, cy, cz, i, r) }
        else cell(tier, cx, cy, cz) { i, r -> if (i == want) out = body(tier, cx, cy, cz, i, r) }
        return out
    }
}
