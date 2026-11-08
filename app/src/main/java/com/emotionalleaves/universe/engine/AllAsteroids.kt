package com.emotionalleaves.universe.engine

import com.emotionalleaves.universe.core.Astro
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Every known asteroid: the JPL Small-Body Database's full list (about 1.5 million orbits), downloaded
 * once by the app and turned into a compact binary file. Elements stay memory-mapped (off the Java
 * heap) and go to the GPU as they are; the GPU solves Kepler's equation for all of them each frame.
 *
 * File elements.bin: "UXAS", version, count, JD of the reference epoch (double), then per asteroid
 * 8 floats: a (AU), e, i, node, perihelion, M at the reference epoch (radians, ecliptic J2000),
 * H, class code. Sorted brightest (lowest H) first, so any prefix is the brightest N.
 * File names.txt: one line per asteroid in the same order, "designation<TAB>name".
 */
class AsteroidDb private constructor(
    val dir: File,
    val count: Int,
    val jdRef: Double,
    /** Where the orbits came from (for the info card). */
    val sourceName: String,
    /** 8 floats per asteroid (see the class comment). */
    val elements: FloatBuffer,
    private val nameOffsets: IntArray,
    private val namesFile: File,
) {
    fun a(i: Int) = elements.get(i * 8).toDouble()
    fun e(i: Int) = elements.get(i * 8 + 1).toDouble()
    fun h(i: Int) = elements.get(i * 8 + 6).toDouble()
    fun cls(i: Int) = elements.get(i * 8 + 7).toInt()

    /** Heliocentric equatorial position in metres. */
    fun position(i: Int, jd: Double): Vec3d {
        val o = i * 8
        val a = elements.get(o).toDouble()
        val n = GAUSS / (a * sqrt(a))
        val m = elements.get(o + 5) + n * (jd - jdRef)
        val ecl = Astro.orbitToPlane(a * Units.AU, elements.get(o + 1).toDouble(), elements.get(o + 2).toDouble(),
            elements.get(o + 4).toDouble(), elements.get(o + 3).toDouble(), m)
        return Astro.eclipticToEquatorial(ecl)
    }

    /** Heliocentric position in AU as floats, written into [out] at [k] (fast path for big loops). */
    fun positionAu(i: Int, dtDays: Double, out: FloatArray, k: Int) {
        val o = i * 8
        val a = elements.get(o).toDouble()
        val e = elements.get(o + 1).toDouble()
        val n = GAUSS / (a * sqrt(a))
        val m = elements.get(o + 5) + n * dtDays
        val ecl = Astro.orbitToPlane(a, e, elements.get(o + 2).toDouble(), elements.get(o + 4).toDouble(), elements.get(o + 3).toDouble(), m)
        val q = Astro.eclipticToEquatorial(ecl)
        out[k] = q.x.toFloat(); out[k + 1] = q.y.toFloat(); out[k + 2] = q.z.toFloat()
    }

    private val raf by lazy { RandomAccessFile(namesFile, "r") }

    /** "packed<TAB>readable" for asteroid [i]. */
    private fun line(i: Int): String = synchronized(this) {
        val start = nameOffsets[i]
        val end = if (i + 1 < count) nameOffsets[i + 1] - 1 else namesFile.length().toInt()
        val buf = ByteArray(max(0, end - start))
        raf.seek(start.toLong())
        raf.readFully(buf)
        String(buf, Charsets.UTF_8).trimEnd('\n', '\r')
    }

    fun packed(i: Int) = line(i).substringBefore('\t')

    /** Display name: "433 Eros", "2024 YR4". */
    fun name(i: Int): String = line(i).substringAfter('\t', "")

    /** Finds asteroids by name or number; scans the names file (about 30 MB) once per query. */
    fun search(query: String, limit: Int = 12): List<Int> {
        val q = normalize(query)
        if (q.length < 2) return emptyList()
        val exact = ArrayList<Int>()
        val prefix = ArrayList<Int>()
        val contains = ArrayList<Int>()
        namesFile.bufferedReader().useLines { lines ->
            for ((i, l) in lines.withIndex()) {
                val readable = l.substringAfter('\t', "")
                val n = normalize(readable)
                val bare = normalize(readable.substringAfter(" ", readable))
                when {
                    n == q || bare == q -> exact.add(i)
                    prefix.size < limit && (bare.startsWith(q) || n.startsWith(q)) -> prefix.add(i)
                    contains.size < limit && q.length >= 4 && n.contains(q) -> contains.add(i)
                }
                if (exact.size >= limit) break
            }
        }
        return (exact + prefix + contains).distinct().take(limit)
    }

    fun indexOfPacked(p: String): Int? {
        namesFile.bufferedReader().useLines { lines ->
            for ((i, l) in lines.withIndex()) if (l.substringBefore('\t') == p) return i
        }
        return null
    }

    fun body(i: Int): MpcAsteroid = MpcAsteroid(this, i, packed(i), name(i))

    companion object {
        const val GAUSS = 0.01720209895   // rad/day for a = 1 AU
        private const val MAGIC = 0x53415855 // "UXAS" little endian

        fun dir(filesDir: File) = File(filesDir, "packs/asteroids-mpc")

        fun installed(filesDir: File) = File(dir(filesDir), "elements.bin").exists() && File(dir(filesDir), "names.txt").exists()

        fun open(filesDir: File): AsteroidDb? = try {
            val d = dir(filesDir)
            val f = File(d, "elements.bin")
            val names = File(d, "names.txt")
            val ch = RandomAccessFile(f, "r").channel
            val map = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN)
            require(map.int == MAGIC)
            map.int
            val count = map.int
            val src = map.int
            val jdRef = map.double
            map.position(24)
            val fb = map.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            require(fb.capacity() >= count * 8)
            // Byte offset of every line in names.txt (for random access when you tap one).
            val offs = IntArray(count)
            var k = 0
            var pos = 0
            names.inputStream().buffered(1 shl 16).use { input ->
                val buf = ByteArray(1 shl 16)
                if (count > 0) offs[k++] = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    for (j in 0 until n) if (buf[j] == '\n'.code.toByte() && k < count) offs[k++] = pos + j + 1
                    pos += n
                }
            }
            AsteroidDb(d, count, jdRef, if (src == 2) "JPL Small-Body Database" else "Minor Planet Center (MPCORB)", fb, offs, names)
        } catch (e: Throwable) {
            ErrorLog.record("opening the asteroid catalogue", e)
            null
        }

        /** "(433) Eros" -> "433 Eros"; "2024 YR4" stays. */
        fun pretty(readable: String): String {
            val r = readable.trim()
            if (r.startsWith("(")) {
                val close = r.indexOf(')')
                if (close > 0) {
                    val num = r.substring(1, close)
                    val rest = r.substring(close + 1).trim()
                    return if (rest.isEmpty()) num else "$num $rest"
                }
            }
            return r
        }

        /** Readable class for a stored class code (see [classCode]). */
        fun className(code: Int): String = when (code) {
            1 -> "Asteroid inside Earth's orbit"
            2 -> "Near-Earth asteroid (Aten, crosses Earth's orbit)"
            3 -> "Near-Earth asteroid (Apollo, crosses Earth's orbit)"
            4 -> "Near-Earth asteroid (Amor)"
            5 -> "Mars-crossing asteroid"
            9 -> "Jupiter trojan (shares Jupiter's orbit)"
            10 -> "Distant object (Centaur or beyond Neptune)"
            11 -> "Inner main-belt asteroid"
            12 -> "Outer main-belt asteroid"
            else -> "Main-belt asteroid"
        }

        const val MPC_URL = "https://www.minorplanetcenter.net/iau/MPCORB/MPCORB.DAT.gz"

        /** A download stream that knows its length (for progress). */
        class SizedStream(s: java.io.InputStream, val size: Long) : java.io.FilterInputStream(s)

        /** Parses one MPCORB line (fixed columns, see the MPC's format description). */
        fun parseMpc(l: String, jdRef: Double): Rec? {
            if (l.length < 103) return null
            return try {
                val h = l.substring(8, 13).trim().toFloatOrNull() ?: 18f
                val epoch = packedEpochJd(l.substring(20, 25).trim()) ?: return null
                val m = l.substring(26, 35).trim().toDouble()
                val peri = l.substring(37, 46).trim().toDouble()
                val node = l.substring(48, 57).trim().toDouble()
                val inc = l.substring(59, 68).trim().toDouble()
                val e = l.substring(70, 79).trim().toDouble()
                val a = l.substring(92, 103).trim().toDouble()
                if (!(a > 0) || e >= 1) return null
                val flags = if (l.length >= 165) l.substring(161, 165).trim().toIntOrNull(16) ?: 0 else 0
                val readable = if (l.length >= 194) l.substring(166, 194).trim() else l.substring(0, 7).trim()
                val name = pretty(readable)
                // Designation: the number for numbered ones ("433"), else the provisional one ("2024 YR4").
                val desig = if (readable.startsWith("(")) readable.substring(1, readable.indexOf(')').coerceAtLeast(1)) else readable
                val n = GAUSS / (a * sqrt(a))
                var mRef = (Math.toRadians(m) + n * (jdRef - epoch)) % (2 * PI)
                if (mRef < 0) mRef += 2 * PI
                val cls = when (flags and 63) { 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 4; 5 -> 5; 9 -> 9; 10 -> 10; else -> 0 }
                Rec(desig, name, floatArrayOf(
                    a.toFloat(), e.toFloat(), Math.toRadians(inc).toFloat(), Math.toRadians(node).toFloat(),
                    Math.toRadians(peri).toFloat(), mRef.toFloat(), h, cls.toFloat(),
                ))
            } catch (_: Exception) {
                null
            }
        }

        /** Decodes an MPC packed epoch ("K25BL") to a Julian date. */
        fun packedEpochJd(p: String): Double? {
            if (p.length != 5) return null
            val century = when (p[0]) { 'I' -> 1800; 'J' -> 1900; 'K' -> 2000; 'L' -> 2100; else -> return null }
            val year = century + (p.substring(1, 3).toIntOrNull() ?: return null)
            val month = packedDigit(p[3]) ?: return null
            val day = packedDigit(p[4]) ?: return null
            return julian(year, month, day.toDouble())
        }

        private fun packedDigit(c: Char): Int? = when (c) {
            in '0'..'9' -> c - '0'
            in 'A'..'V' -> c - 'A' + 10
            else -> null
        }

        fun julian(y0: Int, m0: Int, d: Double): Double {
            var y = y0; var m = m0
            if (m <= 2) { y -= 1; m += 12 }
            val a = y / 100
            val b = 2 - a + a / 4
            return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + d + b - 1524.5
        }

        /** JPL's query service: every asteroid's orbit, paged. */
        const val QUERY = "https://ssd-api.jpl.nasa.gov/sbdb_query.api?fields=full_name,pdes,class,a,e,i,om,w,ma,epoch,H&full-prec=1&sb-kind=a"
        const val PAGE = 100_000

        /**
         * Downloads every asteroid's orbit from the JPL Small-Body Database (about 1.5 million, in
         * pages of 100,000) and builds the binary files, parsing each page as it arrives.
         * [progress] gets 0..1; [cancelled] stops it. Returns how many were stored.
         */
        fun build(filesDir: File, cacheDir: File, open: (String) -> java.io.InputStream,
                  progress: (Float) -> Unit, cancelled: () -> Boolean): Int {
            val target = dir(filesDir)
            val work = File(cacheDir, "asteroid-build").also { it.deleteRecursively(); it.mkdirs() }
            val rawEl = File(work, "el.raw")
            val rawNames = File(work, "names.raw")
            var count = 0
            var srcFlag = 1
            var total = 1_500_000
            val hList = FloatArrayList(1_700_000)
            val nameOff = IntArrayList(1_700_000)
            val jdRef = REF_JD
            java.io.DataOutputStream(rawEl.outputStream().buffered(1 shl 17)).use { elOut ->
                rawNames.outputStream().buffered(1 shl 17).use { nOut ->
                    var namePos = 0
                    val bb = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                    fun store(rec: Rec) {
                        bb.clear()
                        for (v in rec.el) bb.putFloat(v)
                        elOut.write(bb.array(), 0, 32)
                        val nb = (rec.packed + "\t" + rec.readable + "\n").toByteArray(Charsets.UTF_8)
                        nOut.write(nb)
                        nameOff.add(namePos)
                        namePos += nb.size
                        hList.add(rec.el[6])
                        count++
                    }
                    // 1) The Minor Planet Center's file (94 MB compressed). 2) If that fails before any
                    // rows arrive, JPL's query service (larger: ~260 MB, in pages).
                    var mpcOk = false
                    try {
                        val input = open(MPC_URL)
                        val total = (input as? SizedStream)?.size ?: 94_000_000L
                        val counting = CountingStream(input)
                        java.util.zip.GZIPInputStream(counting, 1 shl 16).bufferedReader(Charsets.ISO_8859_1).useLines { lines ->
                            var body = false
                            var n = 0
                            for (l in lines) {
                                if (!body) { if (l.startsWith("-----")) body = true; continue }
                                parseMpc(l, jdRef)?.let { store(it) }
                                if (++n % 5000 == 0) {
                                    if (cancelled()) throw IllegalStateException("Cancelled")
                                    progress((counting.count.toFloat() / total).coerceAtMost(1f) * 0.92f)
                                }
                            }
                        }
                        mpcOk = count > 100_000
                    } catch (e: IllegalStateException) {
                        throw e
                    } catch (e: Exception) {
                        if (count > 0) throw e
                    }
                    if (mpcOk) srcFlag = 1
                    if (!mpcOk && count == 0) {
                        srcFlag = 2
                        var from = 0
                        while (true) {
                            if (cancelled()) throw IllegalStateException("Cancelled")
                            var rows = 0
                            val reported = open("$QUERY&limit=$PAGE&limit-from=$from").bufferedReader(Charsets.UTF_8).use { reader ->
                                SbdbRows.read(reader) { f ->
                                    rows++
                                    fromRow(f, jdRef)?.let { store(it) }
                                    if (rows % 5000 == 0) {
                                        if (cancelled()) throw IllegalStateException("Cancelled")
                                        progress(((from + rows).toFloat() / total).coerceAtMost(1f) * 0.92f)
                                    }
                                }
                            }
                            if (reported > 0) total = reported
                            from += rows
                            if (rows < PAGE || from >= total) break
                        }
                    }
                }
            }
            if (count < 1000) throw IllegalStateException("The asteroid list looked wrong ($count rows)")
            // Brightest first: sort by H.
            val h = hList.toArray()
            val keys = LongArray(count) { ((h[it] + 10f).coerceIn(0f, 2000f) * 1000f).toLong() shl 22 or it.toLong() }
            keys.sort()
            val order = IntArray(count) { (keys[it] and 0x3FFFFF).toInt() }
            progress(0.95f)
            target.deleteRecursively()
            target.mkdirs()
            val nOffs = nameOff.toArray()
            val elMap = RandomAccessFile(rawEl, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
            val nmMap = RandomAccessFile(rawNames, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
            val rawNameLen = rawNames.length()
            java.io.BufferedOutputStream(File(target, "elements.bin.tmp").outputStream(), 1 shl 17).use { out ->
                File(target, "names.txt.tmp").outputStream().buffered(1 shl 17).use { nout ->
                    val head = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                    head.putInt(MAGIC).putInt(1).putInt(count).putInt(srcFlag).putDouble(jdRef)
                    out.write(head.array())
                    val rec = ByteArray(32)
                    val nb = ByteArray(256)
                    for (idx in order) {
                        elMap.position(idx * 32); elMap.get(rec); out.write(rec)
                        val s = nOffs[idx]
                        val e = if (idx + 1 < count) nOffs[idx + 1] else rawNameLen.toInt()
                        val len = minOf(e - s, nb.size)
                        nmMap.position(s); nmMap.get(nb, 0, len); nout.write(nb, 0, len)
                    }
                }
            }
            File(target, "elements.bin.tmp").renameTo(File(target, "elements.bin"))
            File(target, "names.txt.tmp").renameTo(File(target, "names.txt"))
            work.deleteRecursively()
            progress(1f)
            return count
        }

        /** Reference epoch for stored mean anomalies: 2025-01-01. */
        const val REF_JD = 2460676.5

        class Rec(val packed: String, val readable: String, val el: FloatArray)

        /** One query row: full_name, pdes, class, a, e, i, om, w, ma, epoch, H. */
        fun fromRow(f: List<String?>, jdRef: Double): Rec? {
            if (f.size < 11) return null
            val a = f[3]?.toDoubleOrNull() ?: return null
            val e = f[4]?.toDoubleOrNull() ?: return null
            if (!(a > 0) || e >= 1 || e < 0) return null
            val inc = f[5]?.toDoubleOrNull() ?: return null
            val node = f[6]?.toDoubleOrNull() ?: 0.0
            val peri = f[7]?.toDoubleOrNull() ?: 0.0
            val m = f[8]?.toDoubleOrNull() ?: return null
            val epoch = f[9]?.toDoubleOrNull() ?: return null
            val h = f[10]?.toFloatOrNull() ?: 18f
            val n = GAUSS / (a * sqrt(a))
            var mRef = (Math.toRadians(m) + n * (jdRef - epoch)) % (2 * PI)
            if (mRef < 0) mRef += 2 * PI
            return Rec(f[1] ?: return null, displayName(f[0] ?: f[1]!!), floatArrayOf(
                a.toFloat(), e.toFloat(), Math.toRadians(inc).toFloat(), Math.toRadians(node).toFloat(),
                Math.toRadians(peri).toFloat(), mRef.toFloat(), h, classCode(f[2] ?: "").toFloat(),
            ))
        }

        /** "     1 Ceres (A801 AA)" -> "1 Ceres"; "  3708 (1980 FV)" -> "3708 (1980 FV)"; "  (2024 YR4)" -> "2024 YR4". */
        fun displayName(full: String): String {
            val t = full.trim()
            if (t.startsWith("(") && t.endsWith(")")) return t.substring(1, t.length - 1)
            val k = t.indexOf(" (")
            if (k > 0) {
                val head = t.substring(0, k)
                return if (head.all { it.isDigit() }) t else head
            }
            return t
        }

        fun classCode(c: String): Int = when (c) {
            "IEO" -> 1; "ATE" -> 2; "APO" -> 3; "AMO" -> 4; "MCA" -> 5; "TJN" -> 9
            "CEN", "TNO", "PAA", "HYA" -> 10
            "IMB" -> 11; "OMB" -> 12
            else -> 0
        }
    }

    private class CountingStream(private val s: java.io.InputStream) : java.io.FilterInputStream(s) {
        @Volatile var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    private class FloatArrayList(cap: Int) {
        private var a = FloatArray(cap); var size = 0
        fun add(v: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
        fun toArray() = a.copyOf(size)
    }

    private class IntArrayList(cap: Int) {
        private var a = IntArray(cap); var size = 0
        fun add(v: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
        fun toArray() = a.copyOf(size)
    }
}

/**
 * Streams the rows of a JPL SBDB query reply ({"fields":[…],"data":[[…],…],"count":N}) without
 * holding the whole reply in memory. Returns "count" (0 if absent).
 */
object SbdbRows {
    fun read(reader: java.io.Reader, row: (List<String?>) -> Unit): Int {
        val r = java.io.PushbackReader(if (reader is java.io.BufferedReader) reader else java.io.BufferedReader(reader, 1 shl 16), 2)
        var count = 0
        fun next(): Int { var c = r.read(); while (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) c = r.read(); return c }
        fun str(): String {
            val sb = StringBuilder()
            while (true) {
                val c = r.read()
                if (c < 0 || c == '"'.code) break
                if (c == '\\'.code) {
                    when (val e = r.read()) {
                        'n'.code -> sb.append('\n'); 't'.code -> sb.append('\t')
                        'u'.code -> { val h = CharArray(4) { r.read().toChar() }; sb.append(String(h).toInt(16).toChar()) }
                        else -> sb.append(e.toChar())
                    }
                } else sb.append(c.toChar())
            }
            return sb.toString()
        }
        fun bare(first: Int): String {
            val sb = StringBuilder().append(first.toChar())
            while (true) {
                val c = r.read()
                if (c < 0) break
                if (c == ','.code || c == ']'.code || c == '}'.code || c == ' '.code || c == '\n'.code) { r.unread(c); break }
                sb.append(c.toChar())
            }
            return sb.toString()
        }
        fun skip(first: Int) {
            when (first) {
                '"'.code -> str()
                '['.code, '{'.code -> {
                    var depth = 1
                    while (depth > 0) {
                        val c = r.read()
                        if (c < 0) return
                        when (c) { '"'.code -> str(); '['.code, '{'.code -> depth++; ']'.code, '}'.code -> depth-- }
                    }
                }
                else -> bare(first)
            }
        }
        if (next() != '{'.code) throw java.io.IOException("Not a JSON object")
        while (true) {
            var c = next()
            if (c == ','.code) c = next()
            if (c != '"'.code) break
            val key = str()
            if (next() != ':'.code) throw java.io.IOException("Bad JSON")
            val v = next()
            when {
                key == "data" && v == '['.code -> {
                    val fields = ArrayList<String?>(16)
                    while (true) {
                        var d = next()
                        if (d == ','.code) d = next()
                        if (d != '['.code) break
                        fields.clear()
                        while (true) {
                            var x = next()
                            if (x == ','.code) x = next()
                            if (x == ']'.code || x < 0) break
                            if (x == '"'.code) fields.add(str())
                            else { val b = bare(x); fields.add(if (b == "null") null else b) }
                        }
                        row(fields)
                    }
                }
                key == "count" -> {
                    val b = if (v == '"'.code) str() else bare(v)
                    count = b.toIntOrNull() ?: 0
                }
                else -> skip(v)
            }
        }
        return count
    }
}

/** One asteroid from the full list. */
class MpcAsteroid(private val db: AsteroidDb, val index: Int, val packed: String, override val name: String) : Body() {
    override val id = "ast:$packed"
    override val kind = Kind.ASTEROID
    override val source = Source.REAL
    override val radius: Double = 1329.0 / sqrt(0.14) * 10.0.pow(-0.2 * db.h(index)) * 500.0
    override val color = 0xB8A898
    override fun position(jd: Double): UPos = UPos.ZERO + db.position(index, jd)
    override val subtitle get() = AsteroidDb.className(db.cls(index))
    override fun viewDistance() = max(radius * 6.0, 2.0e4)

    override fun facts(jd: Double): List<Pair<String, String>> {
        val a = db.a(index); val e = db.e(index)
        return listOf(
            "Size" to String.format(Locale.US, "about %,.2f km across (estimated from brightness)", radius * 2 / 1000),
            "Orbit" to String.format(Locale.US, "%.2f–%.2f AU from the Sun, every %s", a * (1 - e), a * (1 + e), SolarBody.period(365.25 * a.pow(1.5))),
            "From the Sun now" to String.format(Locale.US, "%.2f AU", db.position(index, jd).length() / Units.AU),
            "Brightness (H)" to String.format(Locale.US, "%.1f", db.h(index)),
            "Designation" to packed,
            "Data" to "${db.sourceName}, one of ${String.format(Locale.US, "%,d", db.count)} known asteroids",
        )
    }
}
