package com.emotionalleaves.universe.engine

import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * The fainter GLADE+ galaxies (most of its 22.5 million), streamed in cells around the camera (20 Mpc
 * cells out to 300 Mpc, 100 Mpc cells beyond). The bright ones are in the downloadable pack.
 */
class GalaxyTiles(private val cacheDir: File) {
    private class Tier(val id: Int, val cellPc: Double, val recordSize: Int, val index: HashMap<Long, LongArray>, val radius: Double)

    /** Loaded galaxies: 4 floats each (x, y, z Mpc, absolute B) and PGC numbers. */
    class Group(val tier: Int, val keys: List<Long>, val center: Vec3d, val data: FloatArray, val pgc: IntArray) {
        val count get() = pgc.size
    }

    @Volatile private var tiers: List<Tier> = emptyList()
    @Volatile var groups: List<Group> = emptyList()
        private set
    @Volatile var status = ""
        private set
    val ready get() = tiers.isNotEmpty()
    val loadedCount get() = groups.sumOf { it.count }

    private val loaded = HashSet<Pair<Int, Long>>()
    @Volatile private var busy = false
    @Volatile private var starting = false
    private var failures = 0
    private var retryAt = 0L
    private val exec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "galaxy-tiles").also { it.priority = Thread.MIN_PRIORITY } }

    private fun dir() = File(cacheDir, "gtiles").also { it.mkdirs() }

    private fun key(x: Int, y: Int, z: Int): Long = ((x.toLong() and 0x1FFFFF) shl 42) or ((y.toLong() and 0x1FFFFF) shl 21) or (z.toLong() and 0x1FFFFF)
    private fun unkey(k: Long, shift: Int): Int = ((k shr shift) and 0x1FFFFF).toInt().let { if (it >= 0x100000) it - 0x200000 else it }

    fun start() {
        if (ready || starting) return
        starting = true
        exec.execute {
            try {
                val list = ArrayList<Tier>()
                for ((id, radius) in listOf(1 to 40.0, 2 to 300.0)) {
                    val file = "galaxy_t$id.bin"
                    val idx = File(dir(), "$file.idx")
                    val sizeFile = File(dir(), "$file.size")
                    try {
                        val total = DataServer.totalSize(file)
                        if (total > 0 && sizeFile.exists() && sizeFile.readText().trim().toLongOrNull() != total) {
                            idx.delete(); File(dir(), "t$id").deleteRecursively()
                        }
                        if (total > 0) sizeFile.writeText(total.toString())
                    } catch (e: Exception) {
                        if (!idx.exists()) throw e
                    }
                    if (!idx.exists() || idx.length() < 24) {
                        val head = DataServer.range(file, 0, 24)
                        val hb = java.nio.ByteBuffer.wrap(head).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        require(hb.int == 0x53545855) { "bad tile file" }
                        hb.int; hb.int; hb.float; hb.int
                        val n = hb.int
                        val body = DataServer.range(file, 24, n * 20L)
                        File(dir(), "$file.idx.tmp").also { it.writeBytes(head + body); it.renameTo(idx) }
                    }
                    val b = java.nio.ByteBuffer.wrap(idx.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    b.int; b.int
                    val tier = b.int
                    val cell = b.float.toDouble()
                    val rec = b.int
                    val n = b.int
                    val map = HashMap<Long, LongArray>(n * 2)
                    for (i in 0 until n) {
                        val cx = b.short.toInt(); val cy = b.short.toInt(); val cz = b.short.toInt(); b.short
                        val count = b.int
                        map[key(cx, cy, cz)] = longArrayOf(count.toLong(), b.long)
                    }
                    list.add(Tier(tier, cell, rec, map, radius))
                }
                tiers = list
                status = "Every GLADE+ galaxy: ready"
            } catch (e: Exception) {
                status = if (e is java.net.UnknownHostException) "Galaxies: offline" else "Galaxies: couldn't load (${e.message ?: e.javaClass.simpleName})"
            } finally {
                starting = false
            }
        }
    }

    /** Forgets everything loaded (zoomed far out: they are too faint to see and only cost memory). */
    fun clear() {
        if (groups.isEmpty() || busy) return
        synchronized(loaded) { groups = emptyList(); loaded.clear() }
    }

    /** Called often with the camera position; loads cells around it, forgets far ones. */
    fun update(cam: UPos) {
        val ts = tiers
        if (ts.isEmpty() || busy || System.currentTimeMillis() < retryAt) return
        val p = (cam - UPos.ZERO) / Units.MPC
        val drop = groups.filter { g -> val t = ts.first { it.id == g.tier }; (g.center - p).length() > t.radius * 2 + t.cellPc * 4 }
        if (drop.isNotEmpty()) synchronized(loaded) {
            groups = groups - drop.toSet()
            for (g in drop) for (k in g.keys) loaded.remove(g.tier to k)
        }
        for (t in ts) {
            val r = t.radius
            val c0 = floor((p.x - r) / t.cellPc).toInt(); val c1 = ceil((p.x + r) / t.cellPc).toInt()
            val d0 = floor((p.y - r) / t.cellPc).toInt(); val d1 = ceil((p.y + r) / t.cellPc).toInt()
            val e0 = floor((p.z - r) / t.cellPc).toInt(); val e1 = ceil((p.z + r) / t.cellPc).toInt()
            val want = ArrayList<Pair<Long, Double>>()
            for (x in c0..c1) for (y in d0..d1) for (z in e0..e1) {
                val k = key(x, y, z)
                if (!t.index.containsKey(k) || synchronized(loaded) { (t.id to k) in loaded }) continue
                val d = (Vec3d((x + 0.5) * t.cellPc, (y + 0.5) * t.cellPc, (z + 0.5) * t.cellPc) - p).length()
                if (d < r + t.cellPc * 0.87) want.add(k to d)
            }
            if (want.isEmpty()) continue
            want.sortBy { it.second }
            val batch = want.take(64).map { it.first }
            busy = true
            exec.execute { fetch(t, batch) }
            return
        }
    }

    private fun fetch(t: Tier, keys: List<Long>) {
        try {
            val cache = File(dir(), "t${t.id}").also { it.mkdirs() }
            val missing = keys.filter { !File(cache, "$it.bin").exists() }
            if (missing.isNotEmpty()) {
                status = "Loading real galaxies around you…"
                val spans = missing.map { k -> val e = t.index[k]!!; Triple(k, e[1], e[0] * t.recordSize) }.sortedBy { it.second }
                var i = 0
                while (i < spans.size) {
                    var j = i
                    var end = spans[i].second + spans[i].third
                    while (j + 1 < spans.size && spans[j + 1].second - end < 256 * 1024 && spans[j + 1].second + spans[j + 1].third - spans[i].second < 24L shl 20) {
                        j++; end = max(end, spans[j].second + spans[j].third)
                    }
                    val start = spans[i].second
                    val bytes = DataServer.range("galaxy_t${t.id}.bin", start, end - start)
                    for (k in i..j) {
                        val (key, off, len) = spans[k]
                        val o = (off - start).toInt()
                        File(cache, "$key.bin").writeBytes(bytes.copyOfRange(o, o + len.toInt()))
                    }
                    i = j + 1
                }
            }
            var n = 0
            for (k in keys) n += (t.index[k]?.get(0) ?: 0L).toInt()
            val data = FloatArray(n * 4)
            val pgc = IntArray(n)
            var m = 0
            var cx = 0.0; var cy = 0.0; var cz = 0.0
            val s = t.cellPc / 65535.0
            for (k in keys) {
                val x0 = unkey(k, 42) * t.cellPc; val y0 = unkey(k, 21) * t.cellPc; val z0 = unkey(k, 0) * t.cellPc
                cx += x0; cy += y0; cz += z0
                val b = java.nio.ByteBuffer.wrap(File(cache, "$k.bin").readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                while (b.remaining() >= 12 && m < n) {
                    val qx = b.short.toInt() and 0xFFFF; val qy = b.short.toInt() and 0xFFFF; val qz = b.short.toInt() and 0xFFFF
                    val absB = b.short / 100f
                    pgc[m] = b.int
                    data[m * 4] = (x0 + qx * s).toFloat(); data[m * 4 + 1] = (y0 + qy * s).toFloat(); data[m * 4 + 2] = (z0 + qz * s).toFloat()
                    data[m * 4 + 3] = absB
                    m++
                }
            }
            val c = keys.size.coerceAtLeast(1)
            val g = Group(t.id, keys, Vec3d(cx / c + t.cellPc / 2, cy / c + t.cellPc / 2, cz / c + t.cellPc / 2),
                if (m == n) data else data.copyOf(m * 4), if (m == n) pgc else pgc.copyOf(m))
            synchronized(loaded) {
                groups = groups + g
                for (k in keys) loaded.add(t.id to k)
            }
            failures = 0
            status = String.format(Locale.US, "%,d more real galaxies loaded around you", loadedCount)
            try { android.util.Log.i("UniverseDiag", "galaxy tiles t${t.id}: ${keys.size} cells, ${g.count} galaxies (${missing.size} downloaded)") } catch (_: Throwable) {}
        } catch (e: Exception) {
            failures++
            retryAt = System.currentTimeMillis() + minOf(60_000L, 2_000L shl minOf(failures, 5))
            status = if (e is java.net.UnknownHostException) "Galaxies: offline" else "Galaxies: couldn't load (${e.message ?: e.javaClass.simpleName})"
        } finally {
            busy = false
        }
    }
}
