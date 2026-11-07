package com.emotionalleaves.universe.engine

import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.core.Rng
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

enum class GalaxyType(val label: String) {
    SPIRAL("Spiral galaxy"),
    BARRED("Barred spiral galaxy"),
    LENTICULAR("Lenticular galaxy"),
    ELLIPTICAL("Elliptical galaxy"),
    IRREGULAR("Irregular galaxy"),
    DWARF("Dwarf galaxy");

    companion object {
        /** From a Hubble type string such as "Sb", "SBc", "E3", "S0", "IBm". */
        fun fromHubble(h: String, seed: Long): GalaxyType {
            val s = h.trim()
            return when {
                s.startsWith("SB") -> BARRED
                s.startsWith("S0") || s.startsWith("SA0") -> LENTICULAR
                s.startsWith("S") -> SPIRAL
                s.startsWith("E") || s.startsWith("cD") -> ELLIPTICAL
                s.startsWith("I") -> IRREGULAR
                else -> when (Rng(seed).nextInt(10)) {
                    in 0..3 -> SPIRAL
                    in 4..5 -> BARRED
                    6 -> LENTICULAR
                    7 -> ELLIPTICAL
                    8 -> IRREGULAR
                    else -> DWARF
                }
            }
        }
    }
}

/**
 * A galaxy's shape as a star density in its own frame (disk in the XY plane, +Z up), in stars per
 * cubic parsec. The Milky Way uses measured values; other galaxies scale the same recipe.
 */
class GalaxyModel(
    val type: GalaxyType,
    /** Disk scale length, pc. */
    val hR: Double,
    /** Disk scale height, pc. */
    val hz: Double,
    /** Central stellar density of the disk, stars/pc^3. */
    val rho0: Double,
    val arms: Int,
    val pitchDeg: Double,
    val armPhase: Double,
    val barLength: Double,
    val barAngle: Double,
    val bulgeScale: Double,
    val bulgeRho: Double,
    val seed: Long,
) {
    val radius: Double = hR * 5.0
    private val tanPitch = tan(Math.toRadians(pitchDeg))

    /** 0..1: how close a point is to a spiral arm. */
    fun armStrength(x: Double, y: Double): Double {
        if (arms == 0) return 0.0
        val r = sqrt(x * x + y * y).coerceAtLeast(1.0)
        val th = atan2(y, x)
        // Log spiral: theta = ln(r / r0) / tan(pitch) + phase
        val spiral = ln(r / 1000.0) / tanPitch
        var best = Double.MAX_VALUE
        for (k in 0 until arms) {
            val phase = armPhase + 2 * PI * k / arms
            var d = (th - spiral - phase) % (2 * PI)
            if (d > PI) d -= 2 * PI
            if (d < -PI) d += 2 * PI
            best = minOf(best, abs(d))
        }
        val w = 0.32 + 120.0 / r // angular half-width, wider near the centre
        return exp(-(best * best) / (2 * w * w))
    }

    /** Stars per cubic parsec at a point (pc, galaxy frame). */
    fun density(x: Double, y: Double, z: Double): Double {
        val r = sqrt(x * x + y * y)
        var rho = 0.0
        if (type != GalaxyType.ELLIPTICAL) {
            val disk = rho0 * exp(-r / hR) * (exp(-abs(z) / hz) + 0.12 * exp(-abs(z) / (hz * 3)))
            val arm = if (arms > 0) 0.55 + 0.9 * armStrength(x, y) else 1.0
            rho += disk * arm
        }
        // Bulge / bar
        val c = cos(barAngle); val s = sin(barAngle)
        val bx = x * c + y * s
        val by = -x * s + y * c
        val q = if (barLength > 0) sqrt((bx / barLength).pow(2) + (by / (barLength * 0.38)).pow(2) + (z / (barLength * 0.28)).pow(2))
        else sqrt(x * x + y * y + (z / 0.7).pow(2)) / bulgeScale
        rho += bulgeRho * exp(-q * 3.0)
        if (type == GalaxyType.ELLIPTICAL) {
            val m = sqrt(x * x + y * y + (z / 0.65).pow(2)) / bulgeScale
            rho += bulgeRho * exp(-7.67 * (m.pow(0.25) - 1.0)).coerceAtMost(1e4)
        }
        // Thin stellar halo
        val rr = sqrt(r * r + z * z) + 500
        rho += rho0 * 2e-4 * (8000 / rr).pow(3.0)
        return rho
    }

    /** Rough total luminosity as an absolute magnitude. */
    val absMag: Double = when (type) {
        GalaxyType.DWARF -> -15.5
        GalaxyType.IRREGULAR -> -17.5
        else -> -20.8 + 5 * log10(4000.0 / hR) * -0.8
    }

    /**
     * A random point drawn roughly in proportion to light (for the glow seen from afar).
     * Returns position (pc, galaxy frame) and a 0..1 "youth" value (1 = blue arm light, 0 = old yellow).
     */
    fun sampleLight(rng: Rng): Pair<Vec3d, Double> {
        val bulgeShare = when (type) {
            GalaxyType.ELLIPTICAL -> 1.0
            GalaxyType.LENTICULAR -> 0.45
            GalaxyType.BARRED -> 0.25
            GalaxyType.DWARF, GalaxyType.IRREGULAR -> 0.05
            else -> 0.18
        }
        if (rng.nextDouble() < bulgeShare) {
            val s = if (barLength > 0 && type != GalaxyType.ELLIPTICAL) barLength * 0.45 else bulgeScale
            val v = Vec3d(rng.gaussian() * s, rng.gaussian() * s * (if (barLength > 0) 0.4 else 1.0), rng.gaussian() * s * 0.6)
            val c = cos(barAngle); val sn = sin(barAngle)
            return Vec3d(v.x * c - v.y * sn, v.x * sn + v.y * c, v.z) to 0.05
        }
        if (type == GalaxyType.IRREGULAR || type == GalaxyType.DWARF) {
            // Clumpy blobs
            val k = rng.nextInt(7)
            val cr = Rng(seed + k)
            val cx = cr.gaussian() * hR * 0.8; val cy = cr.gaussian() * hR * 0.6
            return Vec3d(cx + rng.gaussian() * hR * 0.4, cy + rng.gaussian() * hR * 0.4, rng.gaussian() * hz * 2) to 0.7
        }
        // Exponential disk radius via a Gamma(2) draw, then accept by arm strength.
        for (attempt in 0 until 12) {
            val r = -hR * ln(rng.nextDouble().coerceAtLeast(1e-9) * rng.nextDouble().coerceAtLeast(1e-9))
            val th = rng.nextDouble() * 2 * PI
            val x = r * cos(th); val y = r * sin(th)
            val a = armStrength(x, y)
            if (arms == 0 || rng.nextDouble() < 0.25 + 0.75 * a) {
                val z = (if (rng.nextDouble() < 0.5) -1 else 1) * -hz * ln(rng.nextDouble().coerceAtLeast(1e-9)) * 0.8
                return Vec3d(x, y, z) to (0.3 + 0.7 * a)
            }
        }
        return Vec3d(rng.gaussian() * hR, rng.gaussian() * hR, rng.gaussian() * hz) to 0.3
    }

    companion object {
        /** Our galaxy: 4 arms, 12° pitch, central bar at 27°, local stellar density ~0.1 stars/pc^3. */
        fun milkyWay() = GalaxyModel(
            GalaxyType.BARRED, hR = 2600.0, hz = 300.0, rho0 = 0.1 * exp(8178.0 / 2600.0), arms = 4,
            pitchDeg = 12.0, armPhase = 0.9, barLength = 3500.0, barAngle = Math.toRadians(27.0),
            bulgeScale = 1200.0, bulgeRho = 25.0, seed = 7,
        )

        /** A model for another galaxy, scaled from its size and type. */
        fun forGalaxy(type: GalaxyType, radiusKpc: Double, seed: Long): GalaxyModel {
            val r = Rng(seed)
            val hR = (radiusKpc * 1000 / 4.5).coerceIn(300.0, 12000.0)
            val arms = when (type) {
                GalaxyType.SPIRAL -> 2 + r.nextInt(3)
                GalaxyType.BARRED -> 2 + r.nextInt(2)
                else -> 0
            }
            val bulgeScale = hR * (if (type == GalaxyType.ELLIPTICAL) 1.2 else 0.35)
            return GalaxyModel(
                type, hR, hR * 0.11, 0.1 * exp(3.1) * (2600 / hR).pow(0.5), arms, 10.0 + r.nextDouble() * 18,
                r.nextDouble() * 2 * PI, if (type == GalaxyType.BARRED) hR * 1.2 else 0.0, r.nextDouble() * PI,
                bulgeScale, if (type == GalaxyType.ELLIPTICAL) 2.0 else 15.0 * (2600 / hR), seed,
            )
        }
    }
}

/** A galaxy you can select and fly into. Real position (catalogue) or generated. */
class GalaxyBody(
    override val id: String,
    override val name: String,
    val center: UPos,
    /** Galaxy frame -> world rotation (disk normal = +Z of the frame). */
    val frame: Quatd,
    val model: GalaxyModel,
    val radiusPc: Double,
    val distanceMly: Double,
    override val source: Source,
    private val extra: List<Pair<String, String>>,
    val seed: Long,
    val catalogAbsMag: Double? = null,
) : Body() {
    override val kind = Kind.GALAXY
    override val radius = radiusPc * Units.PC
    val isMilkyWay get() = id == MILKY_WAY_ID
    val absMag: Double get() = catalogAbsMag ?: model.absMag
    override fun position(jd: Double) = center
    override val subtitle get() = model.type.label
    override fun viewDistance() = radius * 3.0
    override val color = 0xE8DCC8

    fun toLocal(p: UPos): Vec3d = frame.conjugate().rotate(p - center) / Units.PC
    fun toWorld(localPc: Vec3d): UPos = center + frame.rotate(localPc * Units.PC)

    override fun facts(jd: Double): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        if (!isMilkyWay) out.add(
            "Distance" to if (distanceMly >= 1000) String.format(Locale.US, "%,.2f billion ly", distanceMly / 1000)
            else String.format(Locale.US, "%,.2f million ly", distanceMly),
        )
        out.add("Size" to String.format(Locale.US, "about %,.0f ly across", radiusPc * 2 * 3.2616))
        out.add("Brightness" to String.format(Locale.US, "absolute magnitude %.1f", absMag))
        if (extra.none { it.first == "Stars" }) {
            // Rough mass-to-light: a Milky Way-like galaxy (M ≈ -21) has ~100 billion stars.
            out.add("Stars" to "about " + com.emotionalleaves.universe.core.Units.bigCount(Math.pow(10.0, -0.4 * (absMag + 21.0)) * 1.0e11))
        }
        out.add("Explore" to "Fly in: every star here can be visited, with its planets and moons")
        out.addAll(extra)
        return out
    }

    companion object {
        const val MILKY_WAY_ID = "gal:milkyway"

        /** Galactic frame axes in equatorial coordinates (IAU), and the Sun's place in the disk. */
        val GC_DIR = Vec3d.fromRaDec(266.40499, -28.93617)
        val NGP_DIR = Vec3d.fromRaDec(192.85948, 27.12825)
        const val SUN_R_PC = 8178.0
        const val SUN_Z_PC = 20.8

        fun milkyWay(): GalaxyBody {
            val x = GC_DIR
            val z = (NGP_DIR - x * NGP_DIR.dot(x)).normalized()
            val y = z.cross(x)
            // Galaxy frame: +X from the centre toward... we put the Sun at (-SUN_R, 0, +SUN_Z).
            val frame = Quatd.fromBasis(x, y, z)
            val center = UPos.ZERO + x * (SUN_R_PC * Units.PC) - z * (SUN_Z_PC * Units.PC)
            return GalaxyBody(
                MILKY_WAY_ID, "Milky Way", center, frame, GalaxyModel.milkyWay(), 26_000.0, 0.0, Source.REAL,
                listOf(
                    "Stars" to "100–400 billion",
                    "Our place" to "Orion Arm, 26,700 ly from the centre",
                    "Data" to "Shape from a model (no complete map exists); nearby stars real",
                ),
                7L,
            )
        }
    }
}

/** Stars generated around the camera inside a galaxy (rules 1-4). */
class GeneratedStars(
    val galaxy: GalaxyBody,
    val anchor: UPos,
    /** 7 floats per star, world orientation, pc relative to [anchor]. */
    val data: FloatArray,
    /** Exact galaxy-frame positions (pc), 3 per star, so bodies match bookmarks exactly. */
    val local: DoubleArray,
    val teff: FloatArray,
    val cellKeys: LongArray,
    val tiers: ByteArray,
    val index: ShortArray,
) {
    val count get() = teff.size

    fun body(i: Int): StarBody {
        val p = galaxy.toWorld(Vec3d(local[i * 3], local[i * 3 + 1], local[i * 3 + 2]))
        return StarField.makeBody(galaxy, p, data[i * 7 + 6].toDouble(), teff[i].toDouble(), cellKeys[i], tiers[i].toInt(), index[i].toInt())
    }
}

object StarField {
    const val TIER_A_CELL = 10.0     // pc: every star (halved in dense regions)
    const val TIER_B_CELL = 100.0    // pc: only luminous stars (halved in dense regions)
    const val TIER_A_RADIUS = 3
    const val TIER_B_RADIUS = 2
    /** Tier C: only supergiants, so the brightest stars stay tappable kilo-parsecs away. */
    const val TIER_C_CELL = 500.0
    const val TIER_C_RADIUS = 2
    const val SUPER_LIMIT = -4.5    // absolute magnitude
    private const val TIER_C = 100   // tier codes 100.. are tier C (code - 100 = halvings)

    /** 0 = all stars, 1 = luminous, 2 = supergiants. */
    fun baseOf(tier: Int) = if (tier >= TIER_C) 2 else tier % 2
    private fun halvings(tier: Int) = if (tier >= TIER_C) tier - TIER_C else tier / 2
    private fun baseCell(base: Int) = when (base) { 0 -> TIER_A_CELL; 1 -> TIER_B_CELL; else -> TIER_C_CELL }

    /**
     * Tier code: even = tier A (all stars), odd = tier B (luminous); code / 2 = how many times the
     * cell size was halved because the region is dense (keeps the star count on screen bounded).
     */
    fun cellSize(tier: Int): Double = baseCell(baseOf(tier)) / (1 shl halvings(tier))

    fun tierFor(base: Int, rho: Double): Int {
        val perCellMax = if (base == 0) 150.0 else 700.0
        val frac = when (base) { 0 -> 1.0; 1 -> luminousFraction; else -> superFraction }
        var k = 0
        while (k < 4) {
            val s = baseCell(base) / (1 shl k)
            val n = rho * s * s * s * frac
            if (n <= perCellMax) break
            k++
        }
        return if (base == 2) TIER_C + k else k * 2 + base
    }
    const val LUMINOUS_LIMIT = 1.5   // absolute magnitude
    /** Brightest apparent magnitude (seen from the Sun) the real catalogue is complete to. */
    @Volatile var catalogLimit = 8.0

    private class Kind(val p: Double, val tMin: Double, val tMax: Double, val mBright: Double, val mFaint: Double, val spect: String)

    private val KINDS = listOf(
        Kind(0.00003, 30000.0, 45000.0, -6.0, -4.0, "O"),
        Kind(0.0013, 10000.0, 30000.0, -4.0, -0.8, "B"),
        Kind(0.006, 7500.0, 10000.0, 0.6, 2.0, "A"),
        Kind(0.030, 6000.0, 7500.0, 2.0, 3.8, "F"),
        Kind(0.076, 5200.0, 6000.0, 4.0, 5.6, "G"),
        Kind(0.121, 3700.0, 5200.0, 5.6, 8.5, "K"),
        Kind(0.700, 2400.0, 3700.0, 8.5, 15.0, "M"),
        Kind(0.005, 3500.0, 5000.0, -1.5, 1.0, "K III"),
        Kind(0.060, 5000.0, 25000.0, 10.0, 15.0, "D"),
    )
    private val TOTAL = KINDS.sumOf { it.p }
    private val LUMINOUS = KINDS.filter { it.mBright < LUMINOUS_LIMIT }
    private val LUM_TOTAL = LUMINOUS.sumOf { k -> k.p * fractionBrighter(k) }

    private fun fractionBrighter(k: Kind, limit: Double = LUMINOUS_LIMIT) = ((limit - k.mBright) / (k.mFaint - k.mBright)).coerceIn(0.0, 1.0)

    /** Fraction of all stars that are luminous enough for tier B. */
    val luminousFraction = LUM_TOTAL / TOTAL

    private val SUPERS = KINDS.filter { it.mBright < SUPER_LIMIT }
    private val SUPER_TOTAL = SUPERS.sumOf { k -> k.p * fractionBrighter(k, SUPER_LIMIT) }
    /** Fraction of all stars that are supergiants (tier C). */
    val superFraction = SUPER_TOTAL / TOTAL

    /** Draws one supergiant for tier C. */
    private fun drawSuper(rng: Rng): Pair<Double, Double> {
        var u = rng.nextDouble() * SUPER_TOTAL
        for (k in SUPERS) {
            val w = k.p * fractionBrighter(k, SUPER_LIMIT)
            if (u < w || k === SUPERS.last()) {
                val f = rng.nextDouble()
                val m = k.mBright + (SUPER_LIMIT - k.mBright) * f
                return m to (k.tMin + (k.tMax - k.tMin) * (1 - f))
            }
            u -= w
        }
        return -5.0 to 35000.0
    }

    /** Draws one star: (absMag, teff, spectral letter). */
    private fun draw(rng: Rng, luminousOnly: Boolean): Triple<Double, Double, String> {
        val list = if (luminousOnly) LUMINOUS else KINDS
        var u = rng.nextDouble() * (if (luminousOnly) LUM_TOTAL else TOTAL)
        for (k in list) {
            val w = if (luminousOnly) k.p * fractionBrighter(k) else k.p
            if (u < w || k === list.last()) {
                val f = rng.nextDouble()
                val mFaint = if (luminousOnly) minOf(k.mFaint, LUMINOUS_LIMIT) else k.mFaint
                val m = k.mBright + (mFaint - k.mBright) * f
                // Hotter stars are brighter within a class (except white dwarfs)
                val tf = if (k.spect == "D") rng.nextDouble() else 1 - f
                val t = k.tMin + (k.tMax - k.tMin) * tf
                return Triple(m, t, k.spect)
            }
            u -= w
        }
        return Triple(10.0, 3000.0, "M")
    }

    fun cellKey(cx: Int, cy: Int, cz: Int): Long =
        ((cx.toLong() and 0x1FFFFF) shl 42) or ((cy.toLong() and 0x1FFFFF) shl 21) or (cz.toLong() and 0x1FFFFF)

    fun unpackCell(k: Long): Triple<Int, Int, Int> {
        fun sx(v: Long): Int = ((v shl 43) shr 43).toInt()
        return Triple(sx(k ushr 42), sx((k ushr 21) and 0x1FFFFF), sx(k and 0x1FFFFF))
    }

    /** Generates tier A and B stars around [camLocalPc] (galaxy frame, pc). */
    fun generate(g: GalaxyBody, camLocalPc: Vec3d): GeneratedStars {
        val outData = ArrayList<Float>(64_000)
        val outLocal = ArrayList<Double>(27_000)
        val outT = ArrayList<Float>(9_000)
        val outCells = ArrayList<Long>(9_000)
        val outTier = ArrayList<Byte>(9_000)
        val outIdx = ArrayList<Short>(9_000)
        val anchorLocal = Vec3d(floor(camLocalPc.x / TIER_B_CELL) * TIER_B_CELL, floor(camLocalPc.y / TIER_B_CELL) * TIER_B_CELL, floor(camLocalPc.z / TIER_B_CELL) * TIER_B_CELL)
        val anchor = g.toWorld(anchorLocal)
        val sunLocal = if (g.isMilkyWay) g.toLocal(UPos.ZERO) else null
        val rho = g.model.density(camLocalPc.x, camLocalPc.y, camLocalPc.z)
        for (base in 0..2) {
            val tier = tierFor(base, rho)
            val size = cellSize(tier)
            val rad = when (base) { 0 -> TIER_A_RADIUS; 1 -> TIER_B_RADIUS; else -> TIER_C_RADIUS }
            val ccx = floor(camLocalPc.x / size).toInt()
            val ccy = floor(camLocalPc.y / size).toInt()
            val ccz = floor(camLocalPc.z / size).toInt()
            for (dx in -rad..rad) for (dy in -rad..rad) for (dz in -rad..rad) {
                val cx = ccx + dx; val cy = ccy + dy; val cz = ccz + dz
                generateCell(g, tier, cx, cy, cz, sunLocal) { local, absMag, teff, i ->
                    val world = g.frame.rotate(local - anchorLocal)
                    val c = StarBody.tempColor(teff)
                    outData.add(world.x.toFloat()); outData.add(world.y.toFloat()); outData.add(world.z.toFloat())
                    outData.add(c[0]); outData.add(c[1]); outData.add(c[2]); outData.add(absMag.toFloat())
                    outLocal.add(local.x); outLocal.add(local.y); outLocal.add(local.z)
                    outT.add(teff.toFloat()); outCells.add(cellKey(cx, cy, cz)); outTier.add(tier.toByte()); outIdx.add(i.toShort())
                }
            }
        }
        return GeneratedStars(
            g, anchor, outData.toFloatArray(), outLocal.toDoubleArray(), outT.toFloatArray(), outCells.toLongArray(), outTier.toByteArray(),
            outIdx.toShortArray(),
        )
    }

    /** Generates one cell. Deterministic: seeded by galaxy, tier and cell coordinates. */
    fun generateCell(
        g: GalaxyBody, tier: Int, cx: Int, cy: Int, cz: Int, sunLocal: Vec3d?,
        emit: (local: Vec3d, absMag: Double, teff: Double, index: Int) -> Unit,
    ) {
        val size = cellSize(tier)
        val base = baseOf(tier)
        val luminous = base == 1
        val center = Vec3d((cx + 0.5) * size, (cy + 0.5) * size, (cz + 0.5) * size)
        var mean = g.model.density(center.x, center.y, center.z) * size * size * size
        if (luminous) mean *= luminousFraction
        if (base == 2) mean *= superFraction
        val rng = Rng(Rng.hash(g.seed, tier.toLong(), cx.toLong(), cy.toLong(), cz.toLong(), SystemFactory.GENERATOR_VERSION.toLong()))
        val n = rng.poisson(mean).coerceAtMost(6000)
        for (i in 0 until n) {
            val local = Vec3d((cx + rng.nextDouble()) * size, (cy + rng.nextDouble()) * size, (cz + rng.nextDouble()) * size)
            val (m, t) = if (base == 2) drawSuper(rng) else draw(rng, luminous).let { it.first to it.second }
            if (base == 0 && m < LUMINOUS_LIMIT) continue // luminous stars belong to tier B
            if (base == 1 && m < SUPER_LIMIT) continue    // supergiants belong to tier C
            if (sunLocal != null) {
                // Real stars win: no generated stars where the real catalogue is complete.
                val d = (local - sunLocal).length()
                if (!luminous && d < 25.0) continue
                val app = m + 5 * log10(max(d, 0.01)) - 5
                if (app < catalogLimit) continue
            }
            emit(local, m, t, i)
        }
    }

    fun makeBody(g: GalaxyBody, pos: UPos, absMag: Double, teff: Double, cell: Long, tier: Int, i: Int): StarBody {
        val (cx, cy, cz) = unpackCell(cell)
        val hash = (Rng.hash(cx.toLong(), cy.toLong(), cz.toLong(), tier.toLong()) ushr 48).toInt() and 0xFFFF
        val prefix = if (g.isMilkyWay) "MW" else shortName(g.name)
        val name = String.format(Locale.US, "%s %04X-%d%s", prefix, hash, i, when (baseOf(tier)) { 1 -> "L"; 2 -> "S"; else -> "" })
        val spect = when {
            teff > 30000 -> "O"
            teff > 10000 -> if (absMag > 9) "DA" else "B"
            teff > 7500 -> if (absMag > 9) "DA" else "A"
            teff > 6000 -> if (absMag > 9) "DF" else "F"
            teff > 5200 -> if (absMag > 9) "DG" else if (absMag < 1.5) "G III" else "G"
            teff > 3700 -> if (absMag < 1.5) "K III" else "K"
            else -> if (absMag < 1.5) "M III" else "M"
        }
        val c = StarBody.tempColor(teff)
        return StarBody(
            "gs:${g.id}:$tier:$cx,$cy,$cz:$i", name, pos, absMag, teff, spect, Source.GENERATED,
            listOf("Data" to "Generated from the ${g.name} model (generator v${SystemFactory.GENERATOR_VERSION})"),
            StarBody.rgbInt(c[0], c[1], c[2]), g.name,
        )
    }

    /** Short code for a galaxy name: "Andromeda Galaxy" -> "Andromeda", "Large Magellanic Cloud" -> "LMC". */
    fun shortName(name: String): String {
        if (name.startsWith("PGC ") || name.startsWith("NGC ") || name.startsWith("IC ")) return name.replace(" ", "")
        val words = name.split(" ").filter { it.isNotEmpty() && it != "Galaxy" }
        return if (words.size <= 1) (words.firstOrNull() ?: name).take(10) else words.joinToString("") { it.take(1).uppercase() }
    }

    /** Rebuilds a generated star from its id (for bookmarks and search). */
    fun fromId(id: String, galaxyById: (String) -> GalaxyBody?): StarBody? {
        // gs:<galaxyId>:<tier>:<cx>,<cy>,<cz>:<i>   (galaxy ids contain one ':')
        val parts = id.removePrefix("gs:").split(":")
        if (parts.size < 5) return null
        val g = galaxyById(parts[0] + ":" + parts[1]) ?: return null
        val tier = parts[2].toIntOrNull() ?: return null
        val c = parts[3].split(",").mapNotNull { it.toIntOrNull() }
        val idx = parts[4].toIntOrNull() ?: return null
        if (c.size != 3) return null
        var found: StarBody? = null
        val sunLocal = if (g.isMilkyWay) g.toLocal(UPos.ZERO) else null
        generateCell(g, tier, c[0], c[1], c[2], sunLocal) { local, m, t, i ->
            if (i == idx) found = makeBody(g, g.toWorld(local), m, t, cellKey(c[0], c[1], c[2]), tier, i)
        }
        return found
    }
}
