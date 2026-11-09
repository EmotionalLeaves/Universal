package com.emotionalleaves.universe.engine

import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Real stars from Gaia DR3 (1.8 billion) around wherever you are in the Milky Way, streamed from the
 * ESA Gaia archive one "bubble" at a time and cached on the phone. Inside a bubble the generator
 * makes only the stars Gaia is too faint to see, so real and generated never double up.
 */
class GaiaStream(private val cacheDir: File) {
    /** A loaded neighbourhood: centre (pc from the Sun, equatorial), radius (pc) and its stars. */
    class Bubble(val key: String, val centerPc: Vec3d, val radiusPc: Double, val catalog: StarCatalog)

    @Volatile var bubble: Bubble? = null
        private set
    /** Every Gaia star within [NEAR_PC] of the Sun, shipped with the app (no download, no wait). */
    @Volatile var near: StarCatalog? = null
        private set
    @Volatile var status = ""
        private set
    @Volatile private var busy = false
    private var failures = 0
    private var retryAt = 0L
    private val exec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "gaia").also { it.priority = Thread.MIN_PRIORITY } }
    /**
     * Downloads: the archive slows right down when one phone sends several queries at once, so the
     * neighbourhoods ahead are fetched one at a time, and the one you are in never waits behind more than one.
     */
    private val net = java.util.concurrent.Executors.newFixedThreadPool(2) { r -> Thread(r, "gaia-net").also { it.priority = Thread.MIN_PRIORITY; it.isDaemon = true } }
    @Volatile private var prefetching = 0
    private val inFlight = ConcurrentHashMap<String, java.util.concurrent.Future<String>>()
    /** Recently built neighbourhoods, so flying back and forth is instant. */
    private val built = object : LinkedHashMap<String, Bubble>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bubble>?) = size > 6
    }
    /** True until the built-in near-Sun stars have been read (nothing near the Sun is downloaded meanwhile). */
    @Volatile private var nearPending = true
    private var lastP: Vec3d? = null
    private var lastT = 0L
    private var heading: Vec3d? = null

    /** Synthetic catalogue ids (30,000,000+) <-> Gaia source ids. */
    val sourceOf = ConcurrentHashMap<Int, Long>()
    private val idOf = ConcurrentHashMap<Long, Int>()
    private var nextId = 30_000_000

    private fun idFor(source: Long): Int = idOf.getOrPut(source) { synchronized(this) { nextId++ }.also { sourceOf[it] = source } }

    /** Reads the bundled near-Sun stars (call on a background thread after the HYG stars load). */
    fun loadNear(context: android.content.Context, rs: RealStars) {
        near = null
        val input = StarFiles.open(context, "stars/gaia_near.bin") ?: run { nearPending = false; return }
        val bytes = input.use { it.readBytes() }
        val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 12 || b.int != 0x4E475855) return   // "UXGN"
        b.int
        val n = b.int
        val rows = ArrayList<DoubleArray>(n)
        for (i in 0 until n) {
            val ra = b.float.toDouble(); val dec = b.float.toDouble(); val plx = b.float.toDouble()
            val g = b.float.toDouble(); val bprp = b.float.toDouble(); val sid = b.long
            rows.add(doubleArrayOf(java.lang.Double.longBitsToDouble(sid), ra, dec, plx, g, bprp))
        }
        near = build(rows, Vec3d.ZERO, NEAR_PC, rs)
        nearPending = false
        status = String.format(Locale.US, "%,d Gaia stars near the Sun (built in)", near?.count ?: 0)
    }

    /** Key and centre of the neighbourhood containing [p] (centres snap to a grid so caches are shared). */
    private fun cell(p: Vec3d, r: Double): Pair<String, Vec3d> {
        val step = r * 0.3
        val c = Vec3d(floor(p.x / step + 0.5) * step, floor(p.y / step + 0.5) * step, floor(p.z / step + 0.5) * step)
        return String.format(Locale.US, "r%d_%d_%d_%d", r.toInt(), (c.x / step).toLong(), (c.y / step).toLong(), (c.z / step).toLong()) to c
    }

    /** True where the built-in stars already cover a neighbourhood. */
    private fun coveredByNear(c: Vec3d, r: Double) = near != null && c.length() + r * 0.6 < NEAR_PC

    /** Called often with the camera position; loads the neighbourhood you are in and the ones ahead. */
    fun update(cam: UPos, rs: RealStars?, onLoaded: () -> Unit) {
        if (rs == null) return
        val p = (cam - UPos.ZERO) / Units.PC
        val now = System.nanoTime()
        // Which way are we going? (for loading ahead)
        lastP?.let { lp ->
            val dt = (now - lastT) / 1e9
            val v = (p - lp) / dt.coerceAtLeast(1e-3)
            if (v.length() > 0.5) heading = v else if (v.length() < 0.01) heading = null
        }
        lastP = p; lastT = now
        val d = p.length()
        if (d > MAX_PC) return
        if (nearPending && d < NEAR_PC + 50) return
        val r = radiusFor(d)
        val (key, c) = cell(p, r)
        // Passing through faster than a neighbourhood could load (a long trip): don't queue downloads
        // for places you'll have left by the time they arrive. The destination is already loading.
        val speed = heading?.length() ?: 0.0
        if (speed > r * 0.4 && synchronized(built) { !built.containsKey(key) } && !File(File(cacheDir, "gaia"), "$key.csv").exists()) return
        // Load ahead: the next neighbourhoods along the way (also during autopilot trips).
        heading?.let { h ->
            val dir = h.normalized()
            for (k in 1..3) {
                val q = p + dir * (r * 0.6 * k)
                if (q.length() > MAX_PC) break
                val rr = radiusFor(q.length())
                val (k2, c2) = cell(q, rr)
                if (!coveredByNear(c2, rr)) prefetch(k2, c2, rr)
            }
        }
        if (coveredByNear(c, r)) {
            if (bubble != null) { bubble = null; onLoaded() }
            return
        }
        val cur = bubble
        if (cur != null && (cur.centerPc - p).length() < cur.radiusPc * 0.35 && cur.radiusPc == r) return
        if (cur?.key == key || busy) return
        synchronized(built) { built[key] }?.let { b -> bubble = b; status = statusFor(b); onLoaded(); return }
        if (System.currentTimeMillis() < retryAt) return
        busy = true
        val t0 = System.nanoTime()
        exec.execute {
            try {
                val rows = parse(download(key, c, r).get())
                val b = Bubble(key, c, r, build(rows, c, r, rs))
                synchronized(built) { built[key] = b }
                bubble = b
                failures = 0
                status = statusFor(b)
                onLoaded()
                try { android.util.Log.i("UniverseDiag", "gaia $key: ${b.catalog.count} stars in %.1f s".format((System.nanoTime() - t0) / 1e9)) } catch (_: Throwable) {}
            } catch (e: Exception) {
                failures++
                retryAt = System.currentTimeMillis() + min(60_000L, 2_000L shl min(failures, 5))
                val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
                status = if (cause is java.net.UnknownHostException) "Gaia: offline" else "Gaia: couldn't load (${cause.message ?: cause.javaClass.simpleName})"
            } finally {
                busy = false
            }
        }
    }

    /**
     * Starts downloading the neighbourhood around a destination as soon as a trip begins, so the
     * stars are usually there when you arrive.
     */
    fun prepare(target: UPos) {
        val p = (target - UPos.ZERO) / Units.PC
        val d = p.length()
        if (d > MAX_PC) return
        val r = radiusFor(d)
        val (key, c) = cell(p, r)
        if (coveredByNear(c, r) || synchronized(built) { built.containsKey(key) }) return
        download(key, c, r)
    }

    private fun statusFor(b: Bubble) = String.format(Locale.US, "%,d Gaia stars around you", b.catalog.count)

    fun clear() { bubble = null }

    private fun prefetch(key: String, c: Vec3d, r: Double) {
        if (inFlight.containsKey(key) || synchronized(built) { built.containsKey(key) }) return
        if (File(File(cacheDir, "gaia"), "$key.csv").exists()) return
        if (prefetching > 0 || busy) return
        prefetching++
        val f = download(key, c, r)
        Thread { try { f.get() } catch (_: Exception) {} finally { prefetching-- } }.also { it.isDaemon = true; it.start() }
    }

    /** The CSV for a neighbourhood: from the phone if fetched before, else from the fastest archive. */
    private fun download(key: String, c: Vec3d, r: Double): java.util.concurrent.Future<String> {
        inFlight[key]?.let { return it }
        val f = net.submit<String> {
            try {
                val dir = File(cacheDir, "gaia").also { it.mkdirs() }
                val file = File(dir, "$key.csv")
                if (file.exists() && file.length() > 0) file.readText() else {
                    if (bubble?.key != key) status = "Loading real Gaia stars around you…"
                    val body = race(query(c, r))
                    File(dir, "$key.tmp").also { it.writeText(body); it.renameTo(file) }
                    trimCache(dir)
                    body
                }
            } finally {
                inFlight.remove(key)
            }
        }
        inFlight[key] = f
        return f
    }

    /**
     * Asks the Gaia archives at once and takes whichever answers first (they differ a lot in speed
     * from hour to hour); the slower request is abandoned.
     */
    private fun race(adql: String): String {
        val q = java.net.URLEncoder.encode(adql, "UTF-8")
        val done = java.util.concurrent.ArrayBlockingQueue<Result<String>>(TAPS.size)
        val threads = TAPS.mapIndexed { i, base ->
            Thread {
                // The backup only starts if the first is slow, so a quick answer costs nothing extra.
                if (i > 0) try { Thread.sleep(6000L * i) } catch (_: InterruptedException) { return@Thread }
                val r = try {
                    val (code, body) = Online.get(base + q, 45_000)
                    if (code == 200 && body.startsWith("source_id")) Result.success(body) else Result.failure(IllegalStateException("archive answered $code"))
                } catch (e: Exception) { Result.failure(e) }
                done.offer(r)
            }.also { it.isDaemon = true; it.start() }
        }
        var last: Throwable? = null
        repeat(TAPS.size) {
            val r = done.poll(50, java.util.concurrent.TimeUnit.SECONDS) ?: return@repeat
            r.onSuccess { body -> threads.forEach { it.interrupt() }; return body }
            last = r.exceptionOrNull()
        }
        throw (last as? Exception) ?: IllegalStateException("no archive answered")
    }

    private fun parse(text: String): List<DoubleArray> {
        val out = ArrayList<DoubleArray>(8192)
        for (line in text.lineSequence().drop(1)) {
            if (line.isEmpty()) continue
            val p = line.split(',')
            if (p.size < 6) continue
            val sid = p[0].toLongOrNull() ?: continue
            val ra = p[1].toDoubleOrNull() ?: continue
            val dec = p[2].toDoubleOrNull() ?: continue
            val plx = p[3].toDoubleOrNull() ?: continue
            val g = p[4].toDoubleOrNull() ?: continue
            val bprp = p[5].toDoubleOrNull() ?: Double.NaN
            out.add(doubleArrayOf(java.lang.Double.longBitsToDouble(sid), ra, dec, plx, g, bprp))
        }
        return out
    }

    private fun trimCache(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total < 150L shl 20) break; total -= f.length(); f.delete() }
    }

    /** Turns rows into a star tile: keeps those inside the bubble, drops ones the HYG catalogue already has. */
    private fun build(rows: List<DoubleArray>, c: Vec3d, r: Double, rs: RealStars): StarCatalog {
        val skipNear = near != null
        // HYG stars inside the bubble, on a 1 pc grid, to skip duplicates.
        val grid = HashMap<Long, MutableList<Pair<Vec3d, Double>>>()
        fun cell(v: Vec3d) = (floor(v.x).toLong() and 0x1FFFFF shl 42) or (floor(v.y).toLong() and 0x1FFFFF shl 21) or (floor(v.z).toLong() and 0x1FFFFF)
        for (cat in rs.allCatalogs()) {
            if (cat === near || cat === bubble?.catalog) continue
            for (tile in cat.tiles) {
            val a = tile.anchorPc
            if (kotlin.math.abs(a.x - c.x) > 101 + r || kotlin.math.abs(a.y - c.y) > 101 + r || kotlin.math.abs(a.z - c.z) > 101 + r) continue
            for (i in 0 until tile.count) {
                val pp = tile.posPc(i)
                if ((pp - c).length() > r + 2) continue
                val dSun = max(pp.length(), 0.01)
                grid.getOrPut(cell(pp)) { ArrayList(2) }.add(pp to tile.absMag(i) + 5 * log10(dSun) - 5)
            }
            }
        }
        val stars = ArrayList<FloatArray>(rows.size)
        val ids = ArrayList<Int>(rows.size)
        for (row in rows) {
            val plx = row[3]
            if (plx <= 0) continue
            val dpc = 1000.0 / plx
            val pos = Vec3d.fromRaDec(row[1], row[2]) * dpc
            if ((pos - c).length() > r) continue
            // Already among the built-in stars near the Sun.
            if (skipNear && dpc < NEAR_PC) continue
            val g = row[4]
            // Already in HYG? (same place within the parallax error, similar brightness)
            if (g < 11.5) {
                val tol = max(0.08, dpc * 0.03)
                var dup = false
                val cx = floor(pos.x).toLong(); val cy = floor(pos.y).toLong(); val cz = floor(pos.z).toLong()
                val span = if (tol > 1) 2 else 1
                loop@ for (dx in -span..span) for (dy in -span..span) for (dz in -span..span) {
                    val key = ((cx + dx) and 0x1FFFFF shl 42) or ((cy + dy) and 0x1FFFFF shl 21) or ((cz + dz) and 0x1FFFFF)
                    for ((hp, hm) in grid[key] ?: continue) {
                        if ((hp - pos).length() < tol && kotlin.math.abs(hm - g) < 1.8) { dup = true; break@loop }
                    }
                }
                if (dup) continue
            }
            val absMag = g + 5 * log10(plx) - 10
            val teff = teffFromBpRp(row[5])
            val col = StarBody.tempColor(teff)
            stars.add(floatArrayOf(pos.x.toFloat(), pos.y.toFloat(), pos.z.toFloat(), col[0], col[1], col[2], absMag.toFloat()))
            val sid = java.lang.Double.doubleToRawLongBits(row[0])
            ids.add(idFor(sid))
            teffs[sid] = teff.toFloat()
        }
        return StarCatalog.fromStars(stars, ids.toIntArray(), "Gaia DR3")
    }

    private val teffs = ConcurrentHashMap<Long, Float>()

    fun teffOf(source: Long) = teffs[source]?.toDouble()

    /** A Gaia star by source id: from the loaded bubble, or looked up in the archive (blocking). */
    fun lookup(source: Long): StarBody? {
        val body = try {
            race("SELECT source_id, ra, dec, parallax, phot_g_mean_mag, bp_rp FROM gaiadr3.gaia_source WHERE source_id = $source")
        } catch (_: Exception) { return null }
        val p = body.lineSequence().drop(1).firstOrNull { it.isNotBlank() }?.split(',') ?: return null
        val ra = p.getOrNull(1)?.toDoubleOrNull() ?: return null
        val dec = p.getOrNull(2)?.toDoubleOrNull() ?: return null
        val plx = p.getOrNull(3)?.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        val g = p.getOrNull(4)?.toDoubleOrNull() ?: return null
        val teff = teffFromBpRp(p.getOrNull(5)?.toDoubleOrNull() ?: Double.NaN)
        val pos = UPos.ZERO + Vec3d.fromRaDec(ra, dec) * (1000.0 / plx * Units.PC)
        return makeBody(source, pos, g + 5 * log10(plx) - 10, teff, plx)
    }

    companion object {
        /** Gaia archives, fastest first: ESA's own (2–4 s a neighbourhood), Heidelberg's mirror as the backup. */
        val TAPS = listOf(
            "https://gea.esac.esa.int/tap-server/tap/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=csv&QUERY=",
            "https://gaia.ari.uni-heidelberg.de/tap/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=csv&QUERY=",
        )
        /** Radius of the built-in Gaia stars around the Sun (pc). */
        const val NEAR_PC = 100.0
        /** Beyond this the parallaxes are too rough to place stars well. */
        const val MAX_PC = 2500.0
        /** Gaia (with the parallax cut used) is complete to about this magnitude seen from the Sun. */
        const val LIMIT_MAG = 18.5

        fun radiusFor(dSunPc: Double) = when {
            dSunPc < 150 -> 20.0
            dSunPc < 600 -> 30.0
            else -> 45.0
        }

        /** The archive query for the stars in a sphere: a cone from the Sun plus a parallax range. */
        fun query(c: Vec3d, r: Double): String {
            val d = c.length()
            val cols = "source_id, ra, dec, parallax, phot_g_mean_mag, bp_rp"
            val poe = if (d < 200) 5 else 10
            return if (d < r * 1.05) {
                // Around the Sun: everything closer than the far edge of the bubble (whole sky).
                "SELECT TOP 50000 $cols FROM gaiadr3.gaia_source WHERE parallax > ${fmt(1000.0 / (d + r))} AND parallax_over_error > $poe AND ruwe < 1.4"
            } else {
                val ra = Math.toDegrees(atan2(c.y, c.x)).let { if (it < 0) it + 360 else it }
                val dec = Math.toDegrees(asin((c.z / d).coerceIn(-1.0, 1.0)))
                val rad = Math.toDegrees(asin((r / d).coerceAtMost(1.0)))
                "SELECT TOP 50000 $cols FROM gaiadr3.gaia_source WHERE 1=CONTAINS(POINT('ICRS',ra,dec),CIRCLE('ICRS',${fmt(ra)},${fmt(dec)},${fmt(rad)})) " +
                    "AND parallax BETWEEN ${fmt(1000.0 / (d + r))} AND ${fmt(1000.0 / (d - r))} AND parallax_over_error > $poe AND ruwe < 1.4"
            }
        }

        private fun fmt(v: Double) = String.format(Locale.US, "%.5f", v)

        /** Effective temperature from Gaia's BP-RP colour (via B-V and Ballesteros' formula). */
        fun teffFromBpRp(c: Double): Double {
            if (c.isNaN()) return 5000.0
            val bv = (c * 0.82 - 0.05).coerceIn(-0.4, 2.2)
            return (4600.0 * (1 / (0.92 * bv + 1.7) + 1 / (0.92 * bv + 0.62))).coerceIn(2300.0, 40000.0)
        }

        fun makeBody(source: Long, pos: UPos, absMag: Double, teff: Double, plx: Double?): StarBody {
            val c = StarBody.tempColor(teff)
            val extra = ArrayList<Pair<String, String>>()
            if (plx != null) extra.add("Parallax" to String.format(Locale.US, "%.3f mas", plx))
            extra.add("Catalogue" to "Gaia DR3 $source")
            extra.add("Data" to "ESA Gaia DR3 (streamed from the Gaia archive)")
            val spect = when {
                teff > 30000 -> "O"; teff > 10000 -> "B"; teff > 7500 -> "A"; teff > 6000 -> "F"
                teff > 5200 -> "G"; teff > 3700 -> "K"; else -> "M"
            } + if (absMag > 10 && teff > 5000) " (white dwarf?)" else ""
            return StarBody("gaia:$source", "Gaia DR3 $source", pos, absMag, teff, spect, Source.REAL, extra, StarBody.rgbInt(c[0], c[1], c[2]))
        }
    }
}
