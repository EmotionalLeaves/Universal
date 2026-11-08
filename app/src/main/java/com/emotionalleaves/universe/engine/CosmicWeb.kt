package com.emotionalleaves.universe.engine

import android.content.Context
import com.emotionalleaves.universe.core.Noise3
import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.core.Rng
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The large-scale structure beyond the galaxy catalogues: galaxies generated along a seeded
 * filament field (ridged noise, ~150 Mpc cells) out to the edge of the observable universe.
 * Real catalogues win: generation starts where the catalogues run out.
 */
class CosmicWebData(
    val innerMpc: Double,
    /** 7 floats per point in Mpc (x, y, z, r, g, b, absMag). */
    val data: FloatArray,
    /** "web" for the observable universe, "out" for the speculative region beyond it. */
    val prefix: String = "web",
) {
    val count get() = data.size / 7

    fun positionMpc(i: Int) = Vec3d(data[i * 7].toDouble(), data[i * 7 + 1].toDouble(), data[i * 7 + 2].toDouble())

    /** An enterable galaxy for generated point [i] (rules 1, 4 and 6). */
    fun galaxy(i: Int): GalaxyBody {
        val seed = if (prefix == "web") Rng.hash(CosmicWeb.SEED, i.toLong(), innerMpc.toLong()) else Rng.hash(CosmicWeb.SEED, 0x0D7L, i.toLong())
        val rng = Rng(seed)
        val p = positionMpc(i)
        val center = UPos.ZERO + p * Units.MPC
        val type = GalaxyType.fromHubble("", seed)
        val absMag = -19.8 + rng.gaussian() * 1.1
        val radiusKpc = (11.0 * 10.0.pow(-0.2 * (absMag + 21.0)) * (0.7 + rng.nextDouble() * 0.6)).coerceIn(0.6, 60.0)
        val n = rng.unitVector()
        val x = Vec3d.perpendicular(n)
        val frame = Quatd.fromBasis(x, n.cross(x).normalized(), n)
        val beyond = prefix == "out"
        val name = String.format(Locale.US, if (beyond) "BX-%04X-%04d" else "GX-%04X-%04d", (seed ushr 48).toInt() and 0xFFFF, i % 10000)
        return GalaxyBody(
            "gal:$prefix$i", name, center, frame, GalaxyModel.forGalaxy(type, radiusKpc, seed), radiusKpc * 1000,
            p.length() * 3.2616, Source.GENERATED,
            if (beyond) listOf(
                "Where" to "Beyond the observable universe: its light can never reach Earth",
                "Data" to "Speculative: the universe probably continues far beyond what we can see; this is imagined to match",
            ) else listOf(
                "Data" to "Generated beyond the catalogues (cosmic web v${CosmicWeb.VERSION})",
                "Light travel" to "Seen as it was long ago; positions are today's (comoving)",
            ),
            seed, absMag,
        )
    }
}

object CosmicWeb {
    const val SEED = 0x51DE5EEDL
    const val VERSION = 1
    /** Fixed so ids stay stable on every phone; quality only changes how many are drawn. */
    const val COUNT = 220_000
    val EDGE_MPC = Units.UNIVERSE_RADIUS / Units.MPC

    fun generate(innerMpc: Double): CosmicWebData {
        val rng = Rng(Rng.hash(SEED, VERSION.toLong(), innerMpc.toLong()))
        val noise = Noise3(SEED)
        val data = FloatArray(COUNT * 7)
        val r0 = innerMpc
        val r1 = EDGE_MPC
        var k = 0
        var guard = 0
        while (k < COUNT && guard < COUNT * 40) {
            guard++
            // Radius with pdf ~ r (more points nearby, each far point stands for more galaxies).
            val r = sqrt(rng.nextDouble() * (r1 * r1 - r0 * r0) + r0 * r0)
            val p = rng.unitVector() * r
            val s = 1.0 / 160.0
            val web = noise.ridged(p.x * s, p.y * s, p.z * s, 4)
            if (rng.nextDouble() > web * web * web * 1.6) continue
            val warm = rng.nextDouble().toFloat()
            data[k * 7] = p.x.toFloat(); data[k * 7 + 1] = p.y.toFloat(); data[k * 7 + 2] = p.z.toFloat()
            data[k * 7 + 3] = 0.85f + 0.15f * warm; data[k * 7 + 4] = 0.85f; data[k * 7 + 5] = 1.0f - 0.2f * warm
            data[k * 7 + 6] = (-21.0 - 2.5 * log10(r / r0) * 0.8).toFloat()
            k++
        }
        return CosmicWebData(innerMpc, if (k == COUNT) data else data.copyOf(k * 7))
    }

    /**
     * How far the speculative "beyond" region reaches, in radii of the observable universe. Galaxies
     * are generated around you anywhere inside it; 9,000 keeps every position exact (64-bit cells).
     */
    const val BEYOND_FACTOR = 9000.0
    /** Tappable sample galaxies drawn out to this many radii (further out, the deep haze shows structure). */
    const val OUTER_POINTS_FACTOR = 12.0
    const val OUTER_COUNT = 200_000

    /** Galaxies beyond the edge of the observable universe (optional, speculative). */
    fun generateOuter(): CosmicWebData {
        val rng = Rng(Rng.hash(SEED, 0x0D7L, VERSION.toLong()))
        val noise = Noise3(SEED)
        val data = FloatArray(OUTER_COUNT * 7)
        val r0 = EDGE_MPC
        val r1 = EDGE_MPC * OUTER_POINTS_FACTOR
        var k = 0
        var guard = 0
        while (k < OUTER_COUNT && guard < OUTER_COUNT * 40) {
            guard++
            val r = sqrt(rng.nextDouble() * (r1 * r1 - r0 * r0) + r0 * r0)
            val p = rng.unitVector() * r
            val s = 1.0 / 160.0
            val web = noise.ridged(p.x * s, p.y * s, p.z * s, 4)
            if (rng.nextDouble() > web * web * web * 1.6) continue
            val warm = rng.nextDouble().toFloat()
            data[k * 7] = p.x.toFloat(); data[k * 7 + 1] = p.y.toFloat(); data[k * 7 + 2] = p.z.toFloat()
            // A slightly different tint marks the speculative region.
            data[k * 7 + 3] = 0.75f + 0.2f * warm; data[k * 7 + 4] = 0.7f; data[k * 7 + 5] = 1.0f
            data[k * 7 + 6] = (-21.0 - 2.5 * log10(r / r0) * 1.4).toFloat()
            k++
        }
        return CosmicWebData(r0, if (k == OUTER_COUNT) data else data.copyOf(k * 7), "out")
    }
}

/** The optional GLADE+ galaxy pack: millions of real galaxies as points (Mpc). */
class GladePack(val data: FloatArray, val maxMpc: Double) {
    val count get() = data.size / 7

    companion object {
        const val PATH = "packs/galaxies-glade/glade.bin"

        fun load(context: Context, maxPoints: Int): GladePack? = try {
            val f = File(context.filesDir, PATH)
            if (!f.exists()) null else {
                val bytes = f.readBytes()
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val magic = ByteArray(4).also { b.get(it) }
                require(String(magic) == "UXGL")
                b.int
                val total = (bytes.size - 8) / 16
                val step = maxOf(1, (total + maxPoints - 1) / maxPoints)
                val n = total / step
                val out = FloatArray(n * 7)
                var maxD = 0.0
                for (i in 0 until n) {
                    b.position(8 + i * step * 16)
                    val x = b.float; val y = b.float; val z = b.float; val m = b.float
                    out[i * 7] = x; out[i * 7 + 1] = y; out[i * 7 + 2] = z
                    out[i * 7 + 3] = 0.92f; out[i * 7 + 4] = 0.9f; out[i * 7 + 5] = 1f
                    out[i * 7 + 6] = m - 2.5f * log10(step.toFloat())
                    val d = sqrt((x * x + y * y + z * z).toDouble())
                    if (d > maxD) maxD = d
                }
                GladePack(out, maxD)
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** The observable universe itself, so you can search for it and fly out to see it whole. */
class UniverseBody : Body() {
    override val id = "universe"
    override val name = "Observable universe"
    override val kind = Kind.UNIVERSE
    override val source = Source.REAL
    override val radius = Units.UNIVERSE_RADIUS
    override fun position(jd: Double) = UPos.ZERO
    override val subtitle get() = "Everything light has had time to reach us from"
    override fun viewDistance() = radius * 2.4
    override fun facts(jd: Double) = listOf(
        "Things in this app" to "${Units.sci(Census.total(true))} with the region beyond the edge (${Units.sci(Census.observable())} without)",
        "Radius" to "about 46 billion light years (comoving)",
        "Age" to "13.8 billion years",
        "Galaxies" to "around 2 trillion (estimate)",
        "Stars" to "roughly 10²² to 10²⁴ — every one can be visited here",
        "Edge" to "Cosmic microwave background, Planck satellite map",
        "Data" to "Nearby galaxies real; distant structure generated",
        "Beyond" to "Unknown: at least hundreds of times larger, maybe infinite (turn on \"Beyond the observable universe\" in Settings)",
    )
}

/**
 * Unresolved light of the large-scale structure: soft blobs along the same filament field as the
 * cosmic web, so walls, filaments and clusters glow when you zoom out (each blob stands for many
 * galaxies too faint to draw one by one). Purely visual: nothing here can be tapped.
 */
object CosmicHaze {
    const val COUNT = 140_000

    /** 7 floats per blob (Mpc from the Sun): x, y, z, r, g, b, weight 0..1. */
    /** Starts at [innerMpc]: closer in, the real galaxy catalogues provide the glow instead. */
    fun generate(innerMpc: Double): FloatArray {
        val rng = Rng(Rng.hash(CosmicWeb.SEED, 0x4A2EL))
        val noise = Noise3(CosmicWeb.SEED)
        val out = FloatArray(COUNT * 7)
        val r0 = innerMpc
        val r1 = CosmicWeb.EDGE_MPC
        var k = 0
        var guard = 0
        while (k < COUNT && guard < COUNT * 60) {
            guard++
            val r = sqrt(rng.nextDouble() * (r1 * r1 - r0 * r0) + r0 * r0)
            val p = rng.unitVector() * r
            val s = 1.0 / 160.0
            val web = noise.ridged(p.x * s, p.y * s, p.z * s, 4)
            if (rng.nextDouble() > web * web * web * web * 2.2) continue
            val node = ((web - 0.55) / 0.4).coerceIn(0.0, 1.0)   // dense knots look warmer (old galaxies)
            out[k * 7] = p.x.toFloat(); out[k * 7 + 1] = p.y.toFloat(); out[k * 7 + 2] = p.z.toFloat()
            out[k * 7 + 3] = (0.55 + 0.45 * node).toFloat()
            out[k * 7 + 4] = (0.62 + 0.2 * node).toFloat()
            out[k * 7 + 5] = (1.0 - 0.35 * node).toFloat()
            out[k * 7 + 6] = (0.35 + 0.65 * web).toFloat()
            k++
        }
        return if (k == COUNT) out else out.copyOf(k * 7)
    }
}

/**
 * Structure of the speculative region beyond the observable universe at every zoom level: cells
 * sized to the current view around the camera, each with a few deterministic glowing blobs
 * (same place, same blobs, every time). Visual only; tappable galaxies come from [GalaxyField].
 */
object DeepHaze {
    class Result(val level: Int, val ccx: Long, val ccy: Long, val ccz: Long, val anchor: UPos, val data: FloatArray, val blobMpc: Double)

    private const val RADIUS = 4
    private const val PER_CELL = 60
    private val noise = Noise3(CosmicWeb.SEED xor 0x7EE9L)

    fun levelFor(viewMetres: Double): Int {
        val gpc = Units.MPC * 1000
        return kotlin.math.floor(kotlin.math.ln(kotlin.math.max(viewMetres / gpc, 1.0)) / kotlin.math.ln(2.0)).toInt().coerceIn(0, 40)
    }

    fun cellMpc(level: Int) = 4000.0 * Math.pow(2.0, level.toDouble())

    fun cellOf(cam: UPos, level: Int): Triple<Long, Long, Long> {
        val c = (cam - UPos.ZERO) / Units.MPC
        val s = cellMpc(level)
        return Triple(kotlin.math.floor(c.x / s).toLong(), kotlin.math.floor(c.y / s).toLong(), kotlin.math.floor(c.z / s).toLong())
    }

    fun generate(level: Int, cell: Triple<Long, Long, Long>): Result {
        val s = cellMpc(level)
        val (ccx, ccy, ccz) = cell
        val anchorMpc = Vec3d(ccx * s, ccy * s, ccz * s)
        val anchor = UPos.ZERO + anchorMpc * Units.MPC
        val edge = CosmicWeb.EDGE_MPC
        val limit = edge * CosmicWeb.BEYOND_FACTOR
        val out = ArrayList<Float>(729 * PER_CELL * 7 / 2)
        for (dx in -RADIUS..RADIUS) for (dy in -RADIUS..RADIUS) for (dz in -RADIUS..RADIUS) {
            val cx = ccx + dx; val cy = ccy + dy; val cz = ccz + dz
            val rng = Rng(Rng.hash(CosmicWeb.SEED, 0xDEE9L, level.toLong(), cx, cy, cz))
            for (k in 0 until PER_CELL) {
                val p = Vec3d((cx + rng.nextDouble()) * s, (cy + rng.nextDouble()) * s, (cz + rng.nextDouble()) * s)
                val r = p.length()
                if (r < edge || r > limit) continue   // inside: the real/observable universe; outside: the end of the model
                val f = 1.0 / (s * 0.6)
                val w = noise.ridged(p.x * f, p.y * f, p.z * f, 3)
                if (rng.nextDouble() > w * w * 1.4) continue
                val rel = p - anchorMpc
                out.add(rel.x.toFloat()); out.add(rel.y.toFloat()); out.add(rel.z.toFloat())
                out.add((0.6 + 0.3 * w).toFloat()); out.add(0.62f); out.add(1f); out.add((0.3 + 0.7 * w).toFloat())
            }
        }
        return Result(level, ccx, ccy, ccz, anchor, out.toFloatArray(), s * 0.7)
    }
}

/**
 * The cosmic web at whatever scale you are looking at, anywhere in the observable universe: seeded
 * cells around the point you are looking at, sized to the zoom level, filled along the same filament
 * field as [CosmicWeb]. Each dot stands for the galaxies of its patch, so its brightness adds up to
 * theirs. Far from home the pre-made web is too sparse to show anything at a 10–1000 Mpc scale.
 */
object WebLod {
    class Result(val level: Int, val ccx: Long, val ccy: Long, val ccz: Long, val anchor: UPos, val data: FloatArray)

    const val RADIUS = 3
    /** Galaxies per cubic Mpc brighter than about the Milky Way's magnitude. */
    private const val DENSITY = 0.012
    private const val ACCEPT = 0.12
    private val noise = Noise3(CosmicWeb.SEED)

    /** Cell edge 2^level Mpc; level 3 = 8 Mpc. */
    fun levelFor(viewMetres: Double): Int {
        val mpc = viewMetres / Units.MPC
        return kotlin.math.floor(kotlin.math.ln(kotlin.math.max(mpc / 2.5, 8.0)) / kotlin.math.ln(2.0)).toInt().coerceIn(3, 12)
    }

    fun cellMpc(level: Int) = Math.pow(2.0, level.toDouble())

    fun cellOf(focus: UPos, level: Int): Triple<Long, Long, Long> {
        val c = (focus - UPos.ZERO) / Units.MPC
        val s = cellMpc(level)
        return Triple(kotlin.math.floor(c.x / s).toLong(), kotlin.math.floor(c.y / s).toLong(), kotlin.math.floor(c.z / s).toLong())
    }

    /** [innerMpc]: catalogues and the near web cover everything closer to Earth than this. */
    fun generate(level: Int, cell: Triple<Long, Long, Long>, innerMpc: Double): Result {
        val s = cellMpc(level)
        val (ccx, ccy, ccz) = cell
        val anchorMpc = Vec3d(ccx * s, ccy * s, ccz * s)
        val edge = CosmicWeb.EDGE_MPC
        // Candidates per cell; about 12% survive the filament test below.
        val perCell = (DENSITY * s * s * s / ACCEPT).coerceIn(1.0, 70.0)
        val represents = kotlin.math.max(1.0, DENSITY * s * s * s / (perCell * ACCEPT))
        val out = ArrayList<Float>(343 * 12 * 7)
        for (dx in -RADIUS..RADIUS) for (dy in -RADIUS..RADIUS) for (dz in -RADIUS..RADIUS) {
            val cx = ccx + dx; val cy = ccy + dy; val cz = ccz + dz
            val rng = Rng(Rng.hash(CosmicWeb.SEED, 0x10DL, level.toLong(), cx, cy, cz))
            val n = perCell.toInt() + if (rng.nextDouble() < perCell - perCell.toInt()) 1 else 0
            // Galaxies come in groups and clusters a few Mpc across: most sit around a few centres per cell.
            val groups = if (s <= 64) Array(1 + rng.nextInt(3)) { Vec3d((cx + rng.nextDouble()) * s, (cy + rng.nextDouble()) * s, (cz + rng.nextDouble()) * s) } else emptyArray()
            val spread = kotlin.math.min(1.2, s * 0.07)
            for (k in 0 until n) {
                val p = if (groups.isNotEmpty() && rng.nextDouble() < 0.7) {
                    val g = groups[rng.nextInt(groups.size)]
                    Vec3d(g.x + rng.gaussian() * spread, g.y + rng.gaussian() * spread, g.z + rng.gaussian() * spread)
                } else Vec3d((cx + rng.nextDouble()) * s, (cy + rng.nextDouble()) * s, (cz + rng.nextDouble()) * s)
                val r = p.length()
                if (r < innerMpc || r > edge) continue
                val w = noise.ridged(p.x / 160.0, p.y / 160.0, p.z / 160.0, 4)
                // Mostly along filaments and in the knots where they meet; voids stay nearly empty.
                val f = kotlin.math.max(0.0, w - 0.48) / 0.32
                if (rng.nextDouble() > 0.012 + f * f) continue
                val warm = rng.nextDouble().toFloat()
                val rel = p - anchorMpc
                out.add(rel.x.toFloat()); out.add(rel.y.toFloat()); out.add(rel.z.toFloat())
                out.add(0.85f + 0.15f * warm); out.add(0.85f); out.add(1.0f - 0.2f * warm)
                out.add((-20.3 + rng.gaussian() * 0.9 - 2.5 * log10(represents) - 1.5 * w).toFloat())
            }
        }
        return Result(level, ccx, ccy, ccz, UPos.ZERO + anchorMpc * Units.MPC, out.toFloatArray())
    }
}
