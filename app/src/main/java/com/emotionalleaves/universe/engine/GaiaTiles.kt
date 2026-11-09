package com.emotionalleaves.universe.engine

import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Every Gaia DR3 star brighter than G = 15 (tens of millions, anywhere in the Milky Way, placed with
 * Bailer-Jones distances), streamed from the public data repo. The data sits in three files of cells
 * (50 / 250 / 1000 pc); the app reads each file's index once, then fetches only the cells around the
 * camera with HTTP range requests and keeps them on the phone.
 */
class GaiaTiles(private val cacheDir: File, private val gaia: GaiaStream) {
    private class Tier(val id: Int, val cellPc: Double, val recordSize: Int, val index: HashMap<Long, LongArray>, val radiusPc: Double, val domainPc: Double)

    /** A batch of cells fetched together and drawn as one catalogue. */
    private class Group(val tier: Int, val keys: List<Long>, val center: Vec3d, val catalog: StarCatalog)

    @Volatile private var tiers: List<Tier> = emptyList()
    @Volatile var catalogs: List<StarCatalog> = emptyList()
        private set
    @Volatile var status = ""
        private set
    /** True once the indexes are in: from then on these tiles stand for every star brighter than G = 15. */
    val ready get() = tiers.isNotEmpty()
    val starsLoaded get() = catalogs.sumOf { it.count }

    private val groups = ArrayList<Group>()
    private val loaded = HashSet<Pair<Int, Long>>()
    @Volatile private var busy = false
    @Volatile private var starting = false
    private var failures = 0
    private var retryAt = 0L
    private val exec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "gaia-tiles").also { it.priority = Thread.MIN_PRIORITY } }

    private fun dir() = File(cacheDir, "tiles").also { it.mkdirs() }

    /** Reads (or downloads once) the three cell indexes. */
    fun start(onReady: () -> Unit) {
        if (ready || starting) return
        starting = true
        exec.execute {
            try {
                val list = ArrayList<Tier>()
                for ((id, radius, domain) in listOf(Triple(1, 120.0, 1300.0), Triple(2, 650.0, 7000.0), Triple(3, 3000.0, 60000.0))) {
                    val file = "gaia_t$id.bin"
                    val idx = File(dir(), "$file.idx")
                    // The data can be rebuilt on the server: if the file's size changed, start the cache afresh.
                    val sizeFile = File(dir(), "$file.size")
                    try {
                        val total = totalSize(file)
                        if (total > 0 && sizeFile.exists() && sizeFile.readText().trim().toLongOrNull() != total) {
                            idx.delete(); File(dir(), "t$id").deleteRecursively()
                        }
                        if (total > 0) sizeFile.writeText(total.toString())
                    } catch (e: Exception) {
                        if (!idx.exists()) throw e   // offline with nothing cached
                    }
                    if (!idx.exists() || idx.length() < 24) {
                        status = "Getting the map of every Gaia star…"
                        val head = range(file, 0, 24)
                        val hb = java.nio.ByteBuffer.wrap(head).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        require(hb.int == 0x53545855) { "bad tile file" } // "UXTS"
                        hb.int; hb.int; hb.float; hb.int
                        val n = hb.int
                        val body = range(file, 24, n * 20L)
                        File(dir(), "$file.idx.tmp").also { it.writeBytes(head + body); it.renameTo(idx) }
                    }
                    val all = idx.readBytes()
                    val b = java.nio.ByteBuffer.wrap(all).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    b.int; b.int
                    val tier = b.int
                    val cell = b.float.toDouble()
                    val rec = b.int
                    val n = b.int
                    val map = HashMap<Long, LongArray>(n * 2)
                    for (i in 0 until n) {
                        val cx = b.short.toInt(); val cy = b.short.toInt(); val cz = b.short.toInt(); b.short
                        val count = b.int
                        val off = b.long
                        map[key(cx, cy, cz)] = longArrayOf(count.toLong(), off)
                    }
                    list.add(Tier(tier, cell, rec, map, radius, domain))
                }
                tiers = list
                status = "Every Gaia star brighter than magnitude 15: ready"
                try { android.util.Log.i("UniverseDiag", "gaia tiles ready: ${list.sumOf { it.index.size }} cells") } catch (_: Throwable) {}
                onReady()
            } catch (e: Exception) {
                status = if (e is java.net.UnknownHostException) "Gaia map: offline" else "Gaia map: couldn't load (${e.message ?: e.javaClass.simpleName})"
                try { android.util.Log.w("UniverseDiag", "gaia tiles index failed: $e") } catch (_: Throwable) {}
            } finally {
                starting = false
            }
        }
    }

    private fun key(x: Int, y: Int, z: Int): Long = ((x.toLong() and 0x1FFFFF) shl 42) or ((y.toLong() and 0x1FFFFF) shl 21) or (z.toLong() and 0x1FFFFF)

    /** Called often with the camera; fetches missing cells around it and forgets far ones. */
    fun update(cam: UPos, rs: RealStars?, onChange: () -> Unit) {
        val ts = tiers
        if (ts.isEmpty() || rs == null || busy) return
        if (System.currentTimeMillis() < retryAt) return
        val p = (cam - UPos.ZERO) / Units.PC
        val dSun = p.length()
        // Forget groups that are far behind.
        val drop = synchronized(groups) {
            groups.filter { g -> val t = ts.first { it.id == g.tier }; (g.center - p).length() > t.radiusPc * 2.2 + t.cellPc * 4 }
        }
        if (drop.isNotEmpty()) {
            synchronized(groups) {
                groups.removeAll(drop.toSet())
                for (g in drop) for (k in g.keys) loaded.remove(g.tier to k)
                catalogs = groups.map { it.catalog }
            }
            onChange()
        }
        // The nearest missing cells of the most detailed tier that applies.
        for (t in ts) {
            if (dSun > t.domainPc + t.radiusPc) continue
            val r = t.radiusPc
            val c0 = floor((p.x - r) / t.cellPc).toInt(); val c1 = ceil((p.x + r) / t.cellPc).toInt()
            val d0 = floor((p.y - r) / t.cellPc).toInt(); val d1 = ceil((p.y + r) / t.cellPc).toInt()
            val e0 = floor((p.z - r) / t.cellPc).toInt(); val e1 = ceil((p.z + r) / t.cellPc).toInt()
            val want = ArrayList<Pair<Long, Double>>()
            for (x in c0..c1) for (y in d0..d1) for (z in e0..e1) {
                val k = key(x, y, z)
                if (!t.index.containsKey(k) || (t.id to k) in loaded) continue
                val cc = Vec3d((x + 0.5) * t.cellPc, (y + 0.5) * t.cellPc, (z + 0.5) * t.cellPc)
                val d = (cc - p).length()
                if (d < r + t.cellPc * 0.87) want.add(k to d)
            }
            if (want.isEmpty()) continue
            want.sortBy { it.second }
            val batch = want.take(48).map { it.first }
            busy = true
            exec.execute { fetch(t, batch, rs, onChange) }
            return
        }
    }

    private fun fetch(t: Tier, keys: List<Long>, rs: RealStars, onChange: () -> Unit) {
        try {
            val pts = ArrayList<GaiaStream.Pt>()
            val cache = File(dir(), "t${t.id}").also { it.mkdirs() }
            val missing = keys.filter { !File(cache, "$it.bin").exists() }
            if (missing.isNotEmpty()) {
                status = "Loading real stars around you…"
                // Merge cells that sit close together in the file into a few range requests.
                val spans = missing.map { k -> val e = t.index[k]!!; Triple(k, e[1], e[0] * t.recordSize) }.sortedBy { it.second }
                var i = 0
                while (i < spans.size) {
                    var j = i
                    var end = spans[i].second + spans[i].third
                    while (j + 1 < spans.size && spans[j + 1].second - end < 256 * 1024 && spans[j + 1].second + spans[j + 1].third - spans[i].second < 24L shl 20) {
                        j++; end = max(end, spans[j].second + spans[j].third)
                    }
                    val start = spans[i].second
                    val bytes = range("gaia_t${t.id}.bin", start, end - start)
                    for (k in i..j) {
                        val (key, off, len) = spans[k]
                        val o = (off - start).toInt()
                        File(cache, "$key.bin").writeBytes(bytes.copyOfRange(o, o + len.toInt()))
                    }
                    i = j + 1
                }
                trim(cache)
            }
            var cx = 0.0; var cy = 0.0; var cz = 0.0
            for (k in keys) {
                val bytes = File(cache, "$k.bin").readBytes()
                val x0 = ((k shr 42) and 0x1FFFFF).let { if (it >= 0x100000) it - 0x200000 else it }.toDouble() * t.cellPc
                val y0 = ((k shr 21) and 0x1FFFFF).let { if (it >= 0x100000) it - 0x200000 else it }.toDouble() * t.cellPc
                val z0 = (k and 0x1FFFFF).let { if (it >= 0x100000) it - 0x200000 else it }.toDouble() * t.cellPc
                cx += x0; cy += y0; cz += z0
                val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val s = t.cellPc / 65535.0
                while (b.remaining() >= 18) {
                    val qx = b.short.toInt() and 0xFFFF; val qy = b.short.toInt() and 0xFFFF; val qz = b.short.toInt() and 0xFFFF
                    val g = (b.short.toInt() and 0xFFFF) / 1000.0
                    val bp = b.short.toInt()
                    val sid = b.long
                    pts.add(GaiaStream.Pt(Vec3d(x0 + qx * s, y0 + qy * s, z0 + qz * s), g, if (bp == -32768) Double.NaN else bp / 1000.0, sid))
                }
            }
            val n = keys.size.coerceAtLeast(1)
            val center = Vec3d(cx / n + t.cellPc / 2, cy / n + t.cellPc / 2, cz / n + t.cellPc / 2)
            var rad = 0.0
            for (pt in pts) rad = max(rad, (pt.pos - center).length())
            val cat = gaia.makeCatalog(pts, center, rad + 1, rs, catalogs.toSet() + setOfNotNull(gaia.near, gaia.bubble?.catalog))
            synchronized(groups) {
                groups.add(Group(t.id, keys, center, cat))
                for (k in keys) loaded.add(t.id to k)
                catalogs = groups.map { it.catalog }
            }
            failures = 0
            status = String.format(Locale.US, "%,d real Gaia stars loaded around you", starsLoaded)
            try { android.util.Log.i("UniverseDiag", "gaia tiles t${t.id}: ${keys.size} cells, ${cat.count} stars (${missing.size} downloaded); loaded $starsLoaded") } catch (_: Throwable) {}
            onChange()
        } catch (e: Exception) {
            failures++
            retryAt = System.currentTimeMillis() + minOf(60_000L, 2_000L shl minOf(failures, 5))
            status = if (e is java.net.UnknownHostException) "Gaia stars: offline" else "Gaia stars: couldn't load (${e.message ?: e.javaClass.simpleName})"
            try { android.util.Log.w("UniverseDiag", "gaia tiles failed: $e") } catch (_: Throwable) {}
        } finally {
            busy = false
        }
    }

    private fun trim(cache: File) {
        val root = dir()
        val files = root.walkTopDown().filter { it.isFile && it.name.endsWith(".bin") }.sortedBy { it.lastModified() }.toList()
        var total = files.sumOf { it.length() }
        for (f in files) { if (total < 600L shl 20) break; total -= f.length(); f.delete() }
    }

    private fun range(file: String, start: Long, len: Long) = DataServer.range(file, start, len)
    private fun totalSize(file: String) = DataServer.totalSize(file)
}
