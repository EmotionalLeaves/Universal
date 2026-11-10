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
import kotlin.math.atan2
import kotlin.math.log10
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
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
    /** The first this-many points are an even sample of the whole universe (drawn alone when zoomed far out). */
    val mapCount: Int = data.size / 7,
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

    /** Density of luminous galaxies (absolute B < -20.5) where the real catalogues are complete, per Mpc³. */
    const val LUMINOUS_DENSITY = 1.0e-3
    const val FILL_BIN_MPC = 100.0

    /** Sky pixels for the fill: equal-area bands in sin(latitude) times equal steps in longitude. */
    const val SKY_Z = 32
    const val SKY_PHI = 64
    const val SKY_PIX = SKY_Z * SKY_PHI
    /** Below this measured completeness a region counts as unsurveyed (pure generated web there). */
    const val MIN_COMPLETENESS = 0.05
    /** Generated companions sit within this distance of the real galaxy they belong to (Mpc). */
    const val COMPANION_MPC = 10.0

    fun skyPixel(x: Double, y: Double, z: Double, r: Double): Int {
        val iz = (((z / r) + 1) * 0.5 * SKY_Z).toInt().coerceIn(0, SKY_Z - 1)
        val ip = (((atan2(y, x) + Math.PI) / (2 * Math.PI)) * SKY_PHI).toInt().coerceIn(0, SKY_PHI - 1)
        return iz * SKY_PHI + ip
    }

    /**
     * Generated galaxies that fill in what the real catalogues miss, out to the edge of the observable
     * universe, following the real structure ([density]: where the real luminous galaxies are).
     *
     * Space is cut into cells (100 Mpc shells x 2048 sky pixels). In each cell the catalogues'
     * completeness is measured (real count / true average count, over the 27 cells around it, so it
     * follows survey edges and the Milky Way's dust band but not single clusters or voids). Then:
     * - complete cells get nothing;
     * - partly surveyed cells get the missing galaxies as companions of their real galaxies (within
     *   10 Mpc, clustered like real galaxies), so real clusters and walls grow and real voids stay empty;
     * - unsurveyed cells (under 5% complete: beyond every survey) get a generated cosmic web.
     */
    fun generateFill(density: RealDensity, budget: Int): CosmicWebData {
        val rng = Rng(Rng.hash(SEED, VERSION.toLong() + 200, density.count.toLong()))
        val noise = Noise3(SEED)
        val bins = RealDensity.BINS
        val start = density.start; val members = density.members
        fun real(b: Int, p: Int) = density.real(b * SKY_PIX + p)
        fun expected(b: Int) = RealDensity.expected(b)
        // Per cell: galaxies missing, and whether they are companions (true) or generated web (false).
        val cellMissing = FloatArray(bins * SKY_PIX)
        val companion = BooleanArray(bins * SKY_PIX)
        val missing = DoubleArray(bins)
        for (b in 0 until bins) {
            val r1 = minOf(EDGE_MPC, (b + 1) * FILL_BIN_MPC)
            // Within 30 Mpc every galaxy is known (and the field generator handles faint ones).
            if (r1 <= 30) continue
            val e = expected(b)
            for (p in 0 until SKY_PIX) {
                val comp = density.comp[b * SKY_PIX + p].toDouble()
                val n = real(b, p)
                val c = b * SKY_PIX + p
                when {
                    comp >= 1.0 -> {}
                    comp >= MIN_COMPLETENESS -> { cellMissing[c] = (n * density.weight * (1.0 / comp - 1.0)).toFloat(); companion[c] = true }
                    else -> cellMissing[c] = max(0.0, e - n * density.weight).toFloat()
                }
                missing[b] += cellMissing[c]
            }
        }
        // Points per shell grow with distance (like the screen area a shell covers), never more than
        // the galaxies actually missing there.
        val share = DoubleArray(bins) { b -> if (missing[b] > 0) sqrt(b + 0.5) else 0.0 }
        val shareSum = share.sum().coerceAtLeast(1e-9)
        val points = IntArray(bins) { b -> minOf(missing[b], budget * share[b] / shareSum).toInt() }
        val total = points.sum()
        val data = FloatArray(total * 7)
        // An even sample over the whole observable universe (same number per volume everywhere) goes
        // first: zoomed far out only that part is drawn, so the near shells don't pile up into a ball.
        val mapDensity = MAP_POINTS / (4.0 / 3.0 * Math.PI * EDGE_MPC * EDGE_MPC * EDGE_MPC)
        val isMap = BooleanArray(total)
        var k = 0
        for (b in 0 until bins) {
            val n = points[b]
            if (n == 0) continue
            val r0 = b * FILL_BIN_MPC; val r1 = minOf(EDGE_MPC, (b + 1) * FILL_BIN_MPC)
            var made = 0
            var guard = 0
            val vol = 4.0 / 3.0 * Math.PI * (r1 * r1 * r1 - r0 * r0 * r0)
            val pMap = (mapDensity * vol / n).coerceAtMost(1.0)
            // Cumulative missing over this shell's sky pixels, to pick a cell for each point.
            val cum = DoubleArray(SKY_PIX)
            var acc = 0.0
            for (p in 0 until SKY_PIX) { acc += cellMissing[b * SKY_PIX + p]; cum[p] = acc }
            while (made < n && guard < n * 60) {
                guard++
                var pix = java.util.Arrays.binarySearch(cum, rng.nextDouble() * acc)
                if (pix < 0) pix = -pix - 1
                pix = pix.coerceIn(0, SKY_PIX - 1)
                val c = b * SKY_PIX + pix
                val p: Vec3d
                if (companion[c]) {
                    val s = start[c]; val m = start[c + 1] - s
                    if (m == 0) continue
                    val host = members[s + (rng.nextDouble() * m).toInt().coerceAtMost(m - 1)]
                    // Pairs of real galaxies are about as common at distance d as d^1.2 (correlation
                    // function ~ d^-1.8), so companion distances are drawn that way.
                    val d = COMPANION_MPC * rng.nextDouble().pow(1.0 / 1.2)
                    p = density.pos(host) + rng.unitVector() * d
                } else {
                    val iz = pix / SKY_PHI; val ip = pix % SKY_PHI
                    val r = Math.cbrt(r0 * r0 * r0 + rng.nextDouble() * (r1 * r1 * r1 - r0 * r0 * r0))
                    val zz = -1.0 + 2.0 * (iz + rng.nextDouble()) / SKY_Z
                    val phi = -Math.PI + 2 * Math.PI * (ip + rng.nextDouble()) / SKY_PHI
                    val rho = sqrt(max(0.0, 1 - zz * zz))
                    p = Vec3d(rho * kotlin.math.cos(phi), rho * kotlin.math.sin(phi), zz) * r
                    val sc = 1.0 / 160.0
                    val web = noise.ridged(p.x * sc, p.y * sc, p.z * sc, 4)
                    if (rng.nextDouble() > web * web * web * 1.6) continue
                }
                val r = p.length()
                val warm = rng.nextDouble().toFloat()
                val m = -20.5 - 0.7 * -ln(1 - rng.nextDouble() * 0.999)
                data[k * 7] = p.x.toFloat(); data[k * 7 + 1] = p.y.toFloat(); data[k * 7 + 2] = p.z.toFloat()
                data[k * 7 + 3] = 0.85f + 0.15f * warm; data[k * 7 + 4] = 0.85f; data[k * 7 + 5] = 1.0f - 0.2f * warm
                // Far points stand for many galaxies but are drawn only a little brighter: their full summed
                // light would turn the whole sky white (each one is a dot, not a tiny smudge).
                data[k * 7 + 6] = (m - 2.0 * log10(max(1.0, r / 300.0))).toFloat()
                isMap[k] = rng.nextDouble() < pMap
                k++; made++
            }
        }
        val inner = (0 until bins).firstOrNull { missing[it] > 0 }?.let { it * FILL_BIN_MPC } ?: EDGE_MPC
        // Map sample first, then the rest; the last float pair records how many are map points.
        val out = FloatArray(k * 7 + 7)
        var o = 0
        for (pass in 0..1) for (i in 0 until k) if (isMap[i] == (pass == 0)) { System.arraycopy(data, i * 7, out, o * 7, 7); o++ }
        val mapN = (0 until k).count { isMap[it] }
        out[k * 7] = Float.NaN; out[k * 7 + 1] = mapN.toFloat()
        return CosmicWebData(inner, out)
    }

    /** Points in the even whole-universe sample of [generateFill]. */
    const val MAP_POINTS = 260_000.0

    /** Splits [generateFill]'s output into the galaxies and the count of map points at the front. */
    fun splitFill(raw: FloatArray): Pair<FloatArray, Int> {
        if (raw.size >= 7 && raw[raw.size - 7].isNaN()) return raw.copyOf(raw.size - 7) to raw[raw.size - 6].toInt()
        return raw to raw.size / 7
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

/**
 * Where the real luminous galaxies (absolute B < -20.5) are, and how complete the catalogues are
 * everywhere: space is cut into 100 Mpc shells x 2048 equal-area sky pixels, and each cell's
 * completeness is its real count over the true average count, measured over the 27 cells around it
 * (so it follows survey footprints, depth and the Milky Way's dust band, not single clusters or voids).
 * Positions are read from [data] (x, y, z Mpc at [stride] floats per galaxy) without copying.
 */
class RealDensity(private val data: Floats, private val stride: Int, private val totalCount: Int, val weight: Double, keep: (Int) -> Boolean) {
    val start = IntArray(BINS * CosmicWeb.SKY_PIX + 1)
    val members: IntArray
    val comp = FloatArray(BINS * CosmicWeb.SKY_PIX)
    val count get() = members.size
    private val total get() = totalCount

    init {
        val n = total
        val cnt = IntArray(BINS * CosmicWeb.SKY_PIX + 1)
        for (i in 0 until n) { val c = cellOfIndex(i, keep); if (c >= 0) cnt[c + 1]++ }
        for (c in 0 until BINS * CosmicWeb.SKY_PIX) start[c + 1] = start[c] + cnt[c + 1]
        members = IntArray(start[BINS * CosmicWeb.SKY_PIX])
        val pos = start.copyOf()
        for (i in 0 until n) { val c = cellOfIndex(i, keep); if (c >= 0) members[pos[c]++] = i }
        val sp = CosmicWeb.SKY_PHI; val sz = CosmicWeb.SKY_Z
        for (b in 0 until BINS) for (p in 0 until CosmicWeb.SKY_PIX) {
            val iz = p / sp; val ip = p % sp
            var sr = 0.0; var se = 0.0
            for (bb in b - 1..b + 1) {
                if (bb < 0 || bb >= BINS) continue
                val eb = expected(bb)
                for (z2 in iz - 1..iz + 1) {
                    if (z2 < 0 || z2 >= sz) continue
                    for (dp in -1..1) { sr += weight * real(bb * CosmicWeb.SKY_PIX + z2 * sp + (ip + dp + sp) % sp); se += eb }
                }
            }
            comp[b * CosmicWeb.SKY_PIX + p] = if (se > 0) (sr / se).toFloat() else 1f
        }
    }

    private fun cellOfIndex(i: Int, keep: (Int) -> Boolean): Int {
        if (!keep(i)) return -1
        val x = data[i * stride].toDouble(); val y = data[i * stride + 1].toDouble(); val z = data[i * stride + 2].toDouble()
        return cellOf(x, y, z)
    }

    fun real(cell: Int) = start[cell + 1] - start[cell]
    fun pos(i: Int) = Vec3d(data[i * stride].toDouble(), data[i * stride + 1].toDouble(), data[i * stride + 2].toDouble())

    /** Completeness at a point (Mpc); 0 beyond the shells. */
    fun completeness(x: Double, y: Double, z: Double): Float { val c = cellOf(x, y, z); return if (c < 0) 0f else comp[c] }

    /** Indices of the real luminous galaxies inside the box [lo, hi] (Mpc). */
    fun inBox(lo: Vec3d, hi: Vec3d, out: (Int) -> Unit) {
        // Every cell the box touches, found by sampling it finely enough for cells beyond ~300 Mpc.
        val cells = HashSet<Int>()
        val steps = 16
        for (a in 0..steps) for (b in 0..steps) for (c in 0..steps) {
            val x = lo.x + (hi.x - lo.x) * a / steps; val y = lo.y + (hi.y - lo.y) * b / steps; val z = lo.z + (hi.z - lo.z) * c / steps
            val cell = cellOf(x, y, z)
            if (cell >= 0) cells += cell
        }
        for (cell in cells) for (k in start[cell] until start[cell + 1]) {
            val i = members[k]
            val x = data[i * stride]; val y = data[i * stride + 1]; val z = data[i * stride + 2]
            if (x >= lo.x && x < hi.x && y >= lo.y && y < hi.y && z >= lo.z && z < hi.z) out(i)
        }
    }

    companion object {
        val BINS = ceil(CosmicWeb.EDGE_MPC / CosmicWeb.FILL_BIN_MPC).toInt()

        fun cellOf(x: Double, y: Double, z: Double): Int {
            val r = sqrt(x * x + y * y + z * z)
            val b = (r / CosmicWeb.FILL_BIN_MPC).toInt()
            if (b >= BINS || r <= 0) return -1
            return b * CosmicWeb.SKY_PIX + CosmicWeb.skyPixel(x, y, z, r)
        }

        /** True average number of luminous galaxies in one cell of shell [b]. */
        fun expected(b: Int): Double {
            val r0 = b * CosmicWeb.FILL_BIN_MPC; val r1 = minOf(CosmicWeb.EDGE_MPC, (b + 1) * CosmicWeb.FILL_BIN_MPC)
            return CosmicWeb.LUMINOUS_DENSITY * 4.0 / 3.0 * Math.PI * (r1 * r1 * r1 - r0 * r0 * r0) / CosmicWeb.SKY_PIX
        }
    }
}

/**
 * A real GLADE+ galaxy as a body you can fly into: GLADE+ gives its place and brightness (and its PGC
 * number when it has one); its shape and the stars inside are generated from those.
 */
fun realGalaxy(id: String, p: Vec3d, absMag: Double, pgc: Int): GalaxyBody {
    val seed = Rng.hash(0x61ADEL, (p.x * 1e4).toLong(), (p.y * 1e4).toLong() xor (p.z * 1e4).toLong())
    val rng = Rng(seed)
    val type = GalaxyType.fromHubble("", seed)
    val radiusKpc = (11.0 * 10.0.pow(-0.2 * (absMag + 21.0)) * (0.7 + rng.nextDouble() * 0.6)).coerceIn(0.6, 60.0)
    val n = rng.unitVector()
    val x = Vec3d.perpendicular(n)
    val frame = Quatd.fromBasis(x, n.cross(x).normalized(), n)
    val ra = Math.toDegrees(kotlin.math.atan2(p.y, p.x)).let { if (it < 0) it + 360 else it }
    val dec = Math.toDegrees(kotlin.math.asin((p.z / p.length()).coerceIn(-1.0, 1.0)))
    val name = if (pgc > 0) "PGC $pgc" else String.format(Locale.US, "GLADE+ J%07.3f%+07.3f", ra, dec)
    return GalaxyBody(
        id, name, UPos.ZERO + p * Units.MPC, frame, GalaxyModel.forGalaxy(type, radiusKpc, seed), radiusKpc * 1000,
        p.length() * 3.2616, Source.REAL,
        listOf(
            "Where" to String.format(Locale.US, "RA %.3f°, Dec %+.3f°", ra, dec),
            "Brightness" to String.format(Locale.US, "absolute magnitude %.1f (B)", absMag),
            "Data" to "GLADE+ galaxy catalogue (position, distance, brightness)" + (if (pgc > 0) "; HyperLEDA PGC $pgc" else "") + "; its shape is generated",
        ),
        seed, absMag,
    )
}

/** Read-only floats, from an array or straight from a mapped file. */
interface Floats {
    operator fun get(i: Int): Float
    /** Copies [n] floats starting at [at] into [out] (fast path for scanning big sets). */
    fun read(at: Int, out: FloatArray, n: Int) { for (k in 0 until n) out[k] = get(at + k) }
}

class ArrayFloats(private val a: FloatArray) : Floats { override fun get(i: Int) = a[i] }

/**
 * The pack file as x, y, z, absolute B groups without copying it onto the heap: group i is the file's
 * group i * [step]; magnitudes are brightened by [magOffset] (each point stands for several galaxies).
 */
class PackFloats(private val buf: java.nio.FloatBuffer, private val step: Int, private val magOffset: Float) : Floats {
    override fun get(i: Int): Float {
        val c = i and 3
        val v = buf.get((i ushr 2) * step * 4 + c)
        return if (c == 3) v + magOffset else v
    }

    override fun read(at: Int, out: FloatArray, n: Int) {
        if (step != 1 || (at and 3) != 0) { super.read(at, out, n); return }
        val b = buf.duplicate()
        b.position(at)
        b.get(out, 0, n)
        if (magOffset != 0f) { var k = 3; while (k < n) { out[k] += magOffset; k += 4 } }
    }
}

/**
 * The galaxy pack: the luminous real galaxies everywhere (GLADE+ with DESI and SDSS redshifts), as
 * points (Mpc): x, y, z, absolute B. [weight]: how many real galaxies each loaded point stands for
 * (phones on lower quality load only part of the pack).
 */
class GladePack(val data: Floats, val count: Int, val maxMpc: Double, val weight: Double = 1.0) {
    /**
     * Galaxy [i] as a body you can fly into. The catalogues give its place and brightness; its shape
     * and the stars inside are generated from those (no name in the pack).
     */
    fun galaxy(i: Int): GalaxyBody =
        realGalaxy("glade:$i", Vec3d(data[i * 4].toDouble(), data[i * 4 + 1].toDouble(), data[i * 4 + 2].toDouble()), data[i * 4 + 3].toDouble(), 0)

    companion object {
        const val PATH = "packs/galaxies-glade/glade.bin"

        fun load(context: Context, maxPoints: Int): GladePack? = try {
            val f = File(context.filesDir, PATH)
            if (!f.exists()) null else {
                // Mapped, not read: the file is over 100 MB and the heap is busy with everything else.
                val b = java.io.RandomAccessFile(f, "r").use { it.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, it.length()) }
                    .order(ByteOrder.LITTLE_ENDIAN)
                val magic = ByteArray(4).also { b.get(it) }
                require(String(magic) == "UXGL")
                val version = b.int
                val total = ((f.length() - 8) / 16).toInt()
                b.position(8)
                val fb = b.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                // Version 2 is shuffled when built (any first N are an even sample of the sky): load the
                // first N. Version 1 is in sky order: take every step-th.
                val step = if (version >= 2) 1 else maxOf(1, (total + maxPoints - 1) / maxPoints)
                val n = if (version >= 2) minOf(total, maxPoints) else total / step
                val weight = total.toDouble() / n
                val data = PackFloats(fb, step, -2.5f * log10(weight.toFloat()))
                var maxD = 0.0
                for (i in 0 until n) {
                    val x = data[i * 4]; val y = data[i * 4 + 1]; val z = data[i * 4 + 2]
                    val d = sqrt((x * x + y * y + z * z).toDouble())
                    if (d > maxD) maxD = d
                }
                GladePack(data, n, maxD, weight)
            }
        } catch (e: Throwable) {
            ErrorLog.record("loading galaxy pack", e)
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
    private const val DENSITY = 0.04
    private const val ACCEPT = 0.15
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

    /** Beyond this the streamed real faint galaxies end, so real luminous ones get generated companions. */
    const val COMPANIONS_FROM_MPC = 300.0
    /** Companions may lie along the line to another real galaxy up to this far away (Mpc). */
    private const val BRIDGE_MPC = 15.0
    /** Galaxies (as counted by [DENSITY]) per luminous galaxy. */
    private const val PER_LUMINOUS = DENSITY / CosmicWeb.LUMINOUS_DENSITY

    /**
     * [innerMpc]: catalogues and the near web cover everything closer to Earth than this.
     * With [density] the galaxies follow the real ones: wherever surveys reach, each real luminous
     * galaxy gets the fainter companions (and missed luminous neighbours) a galaxy like it has, in
     * proportion to how incomplete the catalogues are there; the generated web only fills what no
     * survey has seen (beyond them all, and the band hidden behind the Milky Way's dust).
     */
    fun generate(level: Int, cell: Triple<Long, Long, Long>, innerMpc: Double, density: RealDensity? = null): Result {
        val s = cellMpc(level)
        val (ccx, ccy, ccz) = cell
        val anchorMpc = Vec3d(ccx * s, ccy * s, ccz * s)
        val edge = CosmicWeb.EDGE_MPC
        // Candidates per cell; about 12% survive the filament test below.
        val perCell = (DENSITY * s * s * s / ACCEPT).coerceIn(1.0, 500.0)
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
                if (r > edge) continue
                if (density == null) { if (r < innerMpc) continue }
                else {
                    // Surveyed: the real galaxies' companions (below) fill it. Unsurveyed: generated web,
                    // also nearer than [innerMpc] where the Milky Way hides what lies behind it.
                    if (r < 100 || density.completeness(p.x, p.y, p.z) >= CosmicWeb.MIN_COMPLETENESS) continue
                }
                val w = noise.ridged(p.x / 160.0, p.y / 160.0, p.z / 160.0, 4)
                // Mostly along filaments and in the knots where they meet; voids stay nearly empty.
                val f = kotlin.math.max(0.0, w - 0.48) / 0.32
                if (rng.nextDouble() > 0.05 + f * f) continue
                val warm = rng.nextDouble().toFloat()
                val rel = p - anchorMpc
                out.add(rel.x.toFloat()); out.add(rel.y.toFloat()); out.add(rel.z.toFloat())
                out.add(0.85f + 0.15f * warm); out.add(0.85f); out.add(1.0f - 0.2f * warm)
                out.add((-19.2 + rng.gaussian() * 1.2 - 2.5 * log10(represents) - 1.5 * w).toFloat())
            }
        }
        if (density != null && s <= 64) {
            val lo = Vec3d((ccx - RADIUS) * s, (ccy - RADIUS) * s, (ccz - RADIUS) * s)
            val hi = Vec3d((ccx + RADIUS + 1) * s, (ccy + RADIUS + 1) * s, (ccz + RADIUS + 1) * s)
            val hosts = ArrayList<Int>()
            density.inBox(lo, hi) { hosts += it }
            // Neighbours within BRIDGE_MPC, through a 16 Mpc grid, so companions can lie along the
            // filaments joining real galaxies.
            val grid = HashMap<Long, MutableList<Int>>()
            fun key(v: Vec3d) = (kotlin.math.floor(v.x / 16).toLong() and 0xFFFFF) or ((kotlin.math.floor(v.y / 16).toLong() and 0xFFFFF) shl 20) or ((kotlin.math.floor(v.z / 16).toLong() and 0xFFFFF) shl 40)
            val hp = Array(hosts.size) { density.pos(hosts[it]) }
            for (k in hosts.indices) grid.getOrPut(key(hp[k])) { ArrayList() } += k
            for (hk in hosts.indices) {
                val host = hosts[hk]
                val h = hp[hk]
                val r = h.length()
                if (r < COMPANIONS_FROM_MPC) continue
                val comp = density.completeness(h.x, h.y, h.z).toDouble().coerceIn(CosmicWeb.MIN_COMPLETENESS, 1.0)
                val want = (PER_LUMINOUS * density.weight / comp - 1.0) / represents
                val rng = Rng(Rng.hash(CosmicWeb.SEED, 0xC0AL, level.toLong(), host.toLong()))
                val n = want.toInt() + if (rng.nextDouble() < want - want.toInt()) 1 else 0
                if (n == 0) continue
                val near = ArrayList<Vec3d>()
                val gx = kotlin.math.floor(h.x / 16).toLong(); val gy = kotlin.math.floor(h.y / 16).toLong(); val gz = kotlin.math.floor(h.z / 16).toLong()
                for (ax in -1..1) for (ay in -1..1) for (az in -1..1) {
                    val kk = ((gx + ax) and 0xFFFFF) or (((gy + ay) and 0xFFFFF) shl 20) or (((gz + az) and 0xFFFFF) shl 40)
                    grid[kk]?.forEach { o -> if (o != hk) { val d = hp[o] - h; if (d.length() < BRIDGE_MPC) near += d } }
                }
                for (k in 0 until n) {
                    val u = rng.nextDouble()
                    val off = when {
                        // In the host's own group.
                        u < 0.35 -> Vec3d(rng.gaussian(), rng.gaussian(), rng.gaussian()) * 1.0
                        // Along the filament to a neighbouring real galaxy.
                        u < 0.75 && near.isNotEmpty() -> near[rng.nextInt(near.size)] * rng.nextDouble() +
                            Vec3d(rng.gaussian(), rng.gaussian(), rng.gaussian()) * 0.7
                        // Spread around it like real galaxy pairs (counts growing as d^1.2 out to 10 Mpc).
                        else -> rng.unitVector() * (CosmicWeb.COMPANION_MPC * rng.nextDouble().pow(1.0 / 1.2))
                    }
                    val p = h + off
                    val warm = rng.nextDouble().toFloat()
                    val rel = p - anchorMpc
                    out.add(rel.x.toFloat()); out.add(rel.y.toFloat()); out.add(rel.z.toFloat())
                    out.add(0.85f + 0.15f * warm); out.add(0.85f); out.add(1.0f - 0.2f * warm)
                    out.add((-19.2 + rng.gaussian() * 1.2 - 2.5 * log10(represents)).toFloat())
                }
            }
        }
        return Result(level, ccx, ccy, ccz, UPos.ZERO + anchorMpc * Units.MPC, out.toFloatArray())
    }
}
