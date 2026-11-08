package com.emotionalleaves.universe.engine

import com.emotionalleaves.universe.core.TsvRow

import android.content.Context
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** One spatial tile of catalog stars. Positions are parsecs relative to [anchorPc]. */
class StarTile(
    val anchorPc: Vec3d,
    /** 7 floats per star: dx, dy, dz (pc), r, g, b (0..1), absolute magnitude. */
    val data: FloatArray,
    val ids: IntArray,
    val flags: ByteArray,
) {
    val count get() = ids.size
    fun posPc(i: Int) = Vec3d(anchorPc.x + data[i * 7], anchorPc.y + data[i * 7 + 1], anchorPc.z + data[i * 7 + 2])
    fun absMag(i: Int) = data[i * 7 + 6].toDouble()
}

/** A star catalogue read from the binary tile format written by tools/build_data.py. */
class StarCatalog(val tiles: List<StarTile>, val label: String) {
    companion object Builder {
        /** Builds an in-memory catalogue from stars given in parsecs. */
        fun fromStars(stars: List<FloatArray>, ids: IntArray, label: String, tilePc: Double = 200.0): StarCatalog {
            val groups = LinkedHashMap<Triple<Int, Int, Int>, MutableList<Int>>()
            for ((k, s) in stars.withIndex()) {
                val key = Triple(kotlin.math.floor(s[0] / tilePc).toInt(), kotlin.math.floor(s[1] / tilePc).toInt(), kotlin.math.floor(s[2] / tilePc).toInt())
                groups.getOrPut(key) { ArrayList() }.add(k)
            }
            val tiles = groups.map { (key, members) ->
                val anchor = Vec3d((key.first + 0.5) * tilePc, (key.second + 0.5) * tilePc, (key.third + 0.5) * tilePc)
                val data = FloatArray(members.size * 7)
                val tid = IntArray(members.size)
                for ((j, k) in members.withIndex()) {
                    val s = stars[k]
                    data[j * 7] = (s[0] - anchor.x).toFloat(); data[j * 7 + 1] = (s[1] - anchor.y).toFloat()
                    data[j * 7 + 2] = (s[2] - anchor.z).toFloat()
                    data[j * 7 + 3] = s[3]; data[j * 7 + 4] = s[4]; data[j * 7 + 5] = s[5]; data[j * 7 + 6] = s[6]
                    tid[j] = ids[k]
                }
                StarTile(anchor, data, tid, ByteArray(members.size))
            }
            return StarCatalog(tiles, label)
        }
    }

    val count = tiles.sumOf { it.count }
    /** id -> (tile << 32 | index), built on first use. */
    private val index: HashMap<Int, Long> by lazy {
        val m = HashMap<Int, Long>(count * 2)
        for ((t, tile) in tiles.withIndex()) for (i in 0 until tile.count) m[tile.ids[i]] = (t.toLong() shl 32) or i.toLong()
        m
    }

    fun locate(id: Int): Pair<StarTile, Int>? = index[id]?.let { tiles[(it ushr 32).toInt()] to (it and 0xFFFFFFFFL).toInt() }
}

object StarFiles {
    fun read(input: InputStream, label: String): StarCatalog {
            val bytes = input.readBytes()
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }
            require(String(magic) == "UXST") { "not a star tile file" }
            b.int // version
            val tileCount = b.int
            b.float // tile size
            val anchors = ArrayList<Vec3d>(tileCount)
            val firsts = IntArray(tileCount)
            val counts = IntArray(tileCount)
            for (t in 0 until tileCount) {
                anchors.add(Vec3d(b.double, b.double, b.double))
                firsts[t] = b.int
                counts[t] = b.int
            }
            val tiles = ArrayList<StarTile>(tileCount)
            for (t in 0 until tileCount) {
                val n = counts[t]
                val data = FloatArray(n * 7)
                val ids = IntArray(n)
                val flags = ByteArray(n)
                for (i in 0 until n) {
                    data[i * 7] = b.float; data[i * 7 + 1] = b.float; data[i * 7 + 2] = b.float
                    val mag = b.float
                    data[i * 7 + 3] = (b.get().toInt() and 255) / 255f
                    data[i * 7 + 4] = (b.get().toInt() and 255) / 255f
                    data[i * 7 + 5] = (b.get().toInt() and 255) / 255f
                    flags[i] = b.get()
                    data[i * 7 + 6] = mag
                    ids[i] = b.int
                }
                tiles.add(StarTile(anchors[t], data, ids, flags))
            }
            return StarCatalog(tiles, label)
        }

        fun open(context: Context, path: String): InputStream? = try {
            val pack = File(context.filesDir, "packs/$path")
            if (pack.exists()) pack.inputStream() else context.assets.open(path)
        } catch (_: Exception) {
            null
        }
}

/** Names and details for catalog stars (from hyg_names.tsv). */
class StarInfo(
    val id: Int,
    val proper: String,
    val desig: String,
    val hip: String,
    val hd: String,
    val gl: String,
    val spect: String,
    val distPc: Double,
    val mag: String,
    val teff: Double,
    val base: String,
) {
    fun displayName(): String = when {
        proper.isNotEmpty() -> proper
        desig.isNotEmpty() -> prettyDesig(desig)
        gl.isNotEmpty() -> gl.replace("Gl ", "Gliese ").replace("NN ", "Gliese NN ")
        hip.isNotEmpty() -> "HIP $hip"
        hd.isNotEmpty() -> "HD $hd"
        else -> "HYG $id"
    }

    companion object {
        private val greek = mapOf(
            "Alp" to "α", "Bet" to "β", "Gam" to "γ", "Del" to "δ", "Eps" to "ε", "Zet" to "ζ", "Eta" to "η",
            "The" to "θ", "Iot" to "ι", "Kap" to "κ", "Lam" to "λ", "Mu" to "μ", "Nu" to "ν", "Xi" to "ξ",
            "Omi" to "ο", "Pi" to "π", "Rho" to "ρ", "Sig" to "σ", "Tau" to "τ", "Ups" to "υ", "Phi" to "φ",
            "Chi" to "χ", "Psi" to "ψ", "Ome" to "ω",
        )

        fun prettyDesig(d: String): String {
            val parts = d.split(" ")
            if (parts.size < 2) return d
            val head = parts[0]
            val letters = head.takeWhile { it.isLetter() }
            val rest = head.drop(letters.length)
            val g = greek[letters] ?: return d
            return "$g$rest ${parts[1]}"
        }

        private val row = ThreadLocal.withInitial { TsvRow() }

        fun parse(line: String): StarInfo? {
            val p = row.get()!!.set(line)
            if (p.size < 11) return null
            return StarInfo(
                p.int(0) ?: return null, p.str(1), p.str(2), p.str(3), p.str(4), p.str(5), p.str(6),
                p.num(7) ?: 0.0, p.str(8), p.num(9) ?: 5800.0, p.str(11),
            )
        }
    }
}

/** A star you can select and fly to: from a catalogue or generated. */
class StarBody(
    override val id: String,
    override val name: String,
    val pos: UPos,
    val absMag: Double,
    val teff: Double,
    val spect: String,
    override val source: Source,
    private val extra: List<Pair<String, String>>,
    override val color: Int,
    val galaxyName: String = "Milky Way",
) : Body() {
    override val kind = Kind.STAR
    val luminosity: Double = 10.0.pow(-0.4 * (absMag - 4.83))
    /** Catalogue id for real stars (-1 for generated ones). */
    var catalogId: Int = -1

    /** Radius from luminosity and temperature (Stefan-Boltzmann), clamped to sane values. */
    override val radius: Double = (sqrt(luminosity) * (5772.0 / teff).pow(2) * Units.R_SUN)
        .coerceIn(0.008 * Units.R_SUN, 1500 * Units.R_SUN)

    override fun position(jd: Double) = pos

    override val subtitle: String
        get() = buildString {
            append(if (spect.isNotEmpty()) "Star · ${spect.trim()}" else "Star")
            append(" · ${galaxyName}")
        }

    override fun viewDistance() = radius * 5.0

    override fun facts(jd: Double): List<Pair<String, String>> {
        val d = (pos - UPos.ZERO).length()
        val out = ArrayList<Pair<String, String>>()
        if (galaxyName == "Milky Way") {
            out.add("From the Sun" to Units.formatDistance(d))
            val app = absMag + 5 * log10((d / Units.PC).coerceAtLeast(1e-9)) - 5
            out.add("Brightness from Earth" to String.format(Locale.US, "magnitude %.1f", app))
        }
        out.add("Temperature" to String.format(Locale.US, "%,.0f K", teff))
        out.add("Luminosity" to fmtSolar(luminosity) + " × Sun")
        out.add("Radius" to fmtSolar(radius / Units.R_SUN) + " × Sun")
        out.addAll(Describe.starFacts(this))
        out.addAll(extra)
        return out
    }

    companion object {
        fun fmtSolar(v: Double): String = when {
            v >= 100 -> String.format(Locale.US, "%,.0f", v)
            v >= 1 -> String.format(Locale.US, "%.1f", v)
            v >= 0.01 -> String.format(Locale.US, "%.3f", v)
            else -> String.format(Locale.US, "%.1e", v)
        }

        fun rgbInt(r: Float, g: Float, b: Float): Int =
            ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()

        /** Approximate blackbody colour for a temperature (same formula as the data factory). */
        fun tempColor(t0: Double): FloatArray {
            val t = t0.coerceIn(1000.0, 40000.0) / 100.0
            val r: Double; val g: Double; val b: Double
            if (t <= 66) {
                r = 255.0
                g = 99.4708025861 * kotlin.math.ln(t) - 161.1195681661
                b = if (t <= 19) 0.0 else 138.5177312231 * kotlin.math.ln(t - 10) - 305.0447927307
            } else {
                r = 329.698727446 * (t - 60).pow(-0.1332047592)
                g = 288.1221695283 * (t - 60).pow(-0.0755148492)
                b = 255.0
            }
            return floatArrayOf(
                (r.coerceIn(0.0, 255.0) / 255).toFloat(),
                (g.coerceIn(0.0, 255.0) / 255).toFloat(),
                (b.coerceIn(0.0, 255.0) / 255).toFloat(),
            )
        }
    }
}
