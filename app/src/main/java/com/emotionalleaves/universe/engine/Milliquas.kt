package com.emotionalleaves.universe.engine

import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max

/**
 * The Million Quasars catalogue (Milliquas v8): every quasar and active galaxy with a redshift, kept in
 * flat arrays (a million objects as Kotlin objects would need hundreds of MB). Bodies are made when tapped.
 */
class MilliquasDb private constructor(
    val count: Int,
    /** Position in Mpc from the Sun (equatorial), 3 floats each. */
    val xyz: FloatArray,
    val z: FloatArray,
    val rmag: FloatArray,
    private val nameOffsets: IntArray,
    private val namesFile: File,
) {
    private val raf by lazy { RandomAccessFile(namesFile, "r") }

    private fun line(i: Int): String = synchronized(this) {
        val start = nameOffsets[i]
        val end = if (i + 1 < count) nameOffsets[i + 1] - 1 else namesFile.length().toInt()
        val buf = ByteArray(max(0, end - start))
        raf.seek(start.toLong()); raf.readFully(buf)
        String(buf, Charsets.UTF_8).trimEnd('\n', '\r')
    }

    fun name(i: Int) = line(i).substringBefore('\t').ifBlank { "Quasar" }
    private fun type(i: Int) = line(i).substringAfter('\t', "")

    /** Absolute magnitude for drawing (from R and the luminosity distance). */
    fun absMag(i: Int): Double {
        val dc = Vec3d(xyz[i * 3].toDouble(), xyz[i * 3 + 1].toDouble(), xyz[i * 3 + 2].toDouble()).length() * 1e6
        val m = if (rmag[i].isNaN()) 20.0 else rmag[i].toDouble()
        return m - 5 * log10(max(dc * (1 + z[i]), 1.0)) + 5
    }

    fun body(i: Int): ExoticBody {
        val name = name(i)
        val zz = z[i].toDouble()
        val pos = UPos.ZERO + Vec3d(xyz[i * 3].toDouble(), xyz[i * 3 + 1].toDouble(), xyz[i * 3 + 2].toDouble()) * Units.MPC
        val t = type(i)
        val what = when {
            t.contains('Q') -> "Quasar"
            t.contains('A') -> "Active galactic nucleus"
            t.contains('B') -> "Blazar"
            t.contains('K') || t.contains('N') -> "Narrow-line active galaxy"
            else -> "Quasar candidate"
        }
        val r = rmag[i]
        return ExoticBody(
            "mq:$name", name, Kind.QUASAR, pos, 3e15,
            { listOfNotNull(
                "Type" to "$what (Milliquas class $t)",
                "Redshift" to String.format(Locale.US, "z = %.3f", zz),
                if (!r.isNaN()) "Brightness from Earth" to String.format(Locale.US, "magnitude %.1f (red)", r) else null,
                "Data" to "Million Quasars catalogue (Milliquas v8), ${String.format(Locale.US, "%,d", count)} objects",
            ) },
            "A quasar: the blazing centre of a far-away galaxy, where gas spirals into a supermassive black hole and shines brighter than all the galaxy's stars together.",
            0xC8D8FF,
        ).also { it.mag = if (r.isNaN()) 20.0 else r.toDouble(); it.z = zz }
    }

    fun indexOf(name: String): Int? {
        namesFile.bufferedReader().useLines { lines -> for ((i, l) in lines.withIndex()) if (l.substringBefore('\t') == name) return i }
        return null
    }

    fun search(query: String, limit: Int = 8): List<Int> {
        val q = normalize(query)
        if (q.length < 3) return emptyList()
        val exact = ArrayList<Int>(); val prefix = ArrayList<Int>()
        namesFile.bufferedReader().useLines { lines ->
            for ((i, l) in lines.withIndex()) {
                val n = normalize(l.substringBefore('\t'))
                if (n == q) exact.add(i) else if (prefix.size < limit && n.startsWith(q)) prefix.add(i)
                if (exact.size >= limit) break
            }
        }
        return (exact + prefix).take(limit)
    }

    companion object {
        fun dir(filesDir: File) = File(filesDir, "packs/quasars-milliquas")

        fun load(filesDir: File): MilliquasDb? = try {
            val d = dir(filesDir)
            val bin = File(d, "milliquas.bin")
            val names = File(d, "milliquas_names.txt")
            if (!bin.exists() || !names.exists()) null else {
                val b = RandomAccessFile(bin, "r").use { it.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, it.length()) }
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                require(b.int == 0x514D5855) // "UXMQ"
                b.int
                val n = b.int
                val xyz = FloatArray(n * 3); val z = FloatArray(n); val rm = FloatArray(n)
                val table = ComovingTable()
                for (i in 0 until n) {
                    val ra = b.float.toDouble(); val de = b.float.toDouble(); val zz = b.float.toDouble(); val r = b.float
                    val p = Vec3d.fromRaDec(ra, de) * table.mpc(zz)
                    xyz[i * 3] = p.x.toFloat(); xyz[i * 3 + 1] = p.y.toFloat(); xyz[i * 3 + 2] = p.z.toFloat()
                    z[i] = zz.toFloat(); rm[i] = r
                }
                val offs = IntArray(n)
                var k = 0; var pos = 0
                names.inputStream().buffered(1 shl 16).use { input ->
                    val buf = ByteArray(1 shl 16)
                    if (n > 0) offs[k++] = 0
                    while (true) {
                        val m = input.read(buf)
                        if (m < 0) break
                        for (j in 0 until m) if (buf[j] == '\n'.code.toByte() && k < n) offs[k++] = pos + j + 1
                        pos += m
                    }
                }
                MilliquasDb(n, xyz, z, rm, offs, names)
            }
        } catch (e: Throwable) {
            ErrorLog.record("opening the quasar catalogue", e)
            null
        }
    }

    /** Comoving distance by redshift, tabulated once (a million integrals would take seconds). */
    private class ComovingTable {
        private val step = 0.005
        private val table = DoubleArray(2001) { Famous.comovingMpc(it * step) }
        fun mpc(z: Double): Double {
            if (z <= 0) return 0.0
            val f = z / step
            val i = f.toInt()
            if (i >= table.size - 1) return Famous.comovingMpc(z)
            val t = f - i
            return table[i] * (1 - t) + table[i + 1] * t
        }
    }
}
