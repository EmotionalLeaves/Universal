package com.emotionalleaves.universe.engine

import android.content.Context
import com.emotionalleaves.universe.core.Noise3
import com.emotionalleaves.universe.core.Rng
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** A global elevation map (int16 metres, equirectangular, row 0 = north, column 0 = longitude -180). */
class HeightMap(val w: Int, val h: Int, private val data: ShortArray) {
    /** Bilinear height in metres for a unit direction in the body-fixed frame. */
    fun sample(d: Vec3d): Double {
        val lon = atan2(d.y, d.x)
        val lat = asin(d.z.coerceIn(-1.0, 1.0))
        val fx = (lon + PI) / (2 * PI) * w - 0.5
        val fy = (PI / 2 - lat) / PI * h - 0.5
        val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
        val tx = fx - x0; val ty = fy - y0
        fun at(x: Int, y: Int): Double {
            val xx = ((x % w) + w) % w
            val yy = y.coerceIn(0, h - 1)
            return data[yy * w + xx].toDouble()
        }
        val a = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * tx
        val b = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * tx
        return a + (b - a) * ty
    }

    companion object {
        fun load(context: Context, path: String): HeightMap? = try {
            val f = File(context.filesDir, "packs/$path")
            val bytes = if (f.exists()) f.readBytes() else context.assets.open(path).use { it.readBytes() }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }
            require(String(magic) == "UXHT")
            val w = b.int; val h = b.int
            val arr = ShortArray(w * h)
            b.asShortBuffer().get(arr)
            HeightMap(w, h, arr)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Height function of one solid world: real elevation where we have it (Earth, Moon, Mars),
 * plus seeded fractal detail all the way down to metres. Generated worlds are fully fractal.
 */
class TerrainModel(
    val bodyId: String,
    val radius: Double,
    private val map: HeightMap?,
    seed: Long,
    /** Amplitude of large generated relief (metres) when there is no real map. */
    private val reliefAmp: Double,
    /** Amplitude of fine fractal detail (metres). */
    private val detailAmp: Double,
    /** Heights below this are sea (null = no sea). */
    val seaLevel: Double?,
    /** 0 rocky, 1 cratered, 2 icy, 3 lava. */
    val flavor: Int,
) {
    private val noise = Noise3(seed)
    private val noise2 = Noise3(seed xor 0x5DEECE66DL)

    /** Max level of the quadtree so the finest tiles are a few metres across. */
    val maxLevel: Int = (kotlin.math.ln(radius * PI / 2 / 3.0) / kotlin.math.ln(2.0)).toInt().coerceIn(6, 23)

    /** Height above the reference radius (metres), for a unit direction in the body frame. */
    fun height(d: Vec3d): Double = heightAndDetail(d)[0]

    /** Returns [height, detail] where detail in -1..1 is used for colour variation (negative = water). */
    fun heightAndDetail(d: Vec3d): DoubleArray {
        // Large-scale shape
        var h = if (map != null) map.sample(d) else {
            val c = noise.fbm(d.x * 2.2, d.y * 2.2, d.z * 2.2, 6)
            val r = noise2.ridged(d.x * 5.0 + 3.1, d.y * 5.0, d.z * 5.0, 5)
            reliefAmp * (c * 0.8 + (r - 0.35) * 0.9)
        }
        // Craters for airless worlds
        if (flavor == 1 && map == null) h += craters(d) * reliefAmp * 0.5
        val water = seaLevel != null && h < seaLevel
        // Fine detail: wavelengths from ~40 km down to ~2 m, amplitude falls with wavelength.
        val s = radius / 40_000.0
        val octaves = (kotlin.math.ln(40_000.0 / 2.0) / kotlin.math.ln(2.0)).toInt()
        var detail = 0.0
        if (!water) {
            var amp = 1.0
            var f = s
            var sum = 0.0
            var norm = 0.0
            for (i in 0 until octaves) {
                val n = noise.noise(d.x * f + i * 1.7, d.y * f - i * 2.3, d.z * f + i * 0.9)
                sum += n * amp
                norm += amp
                amp *= 0.48
                f *= 2.0
            }
            detail = sum / norm
            val landFactor = if (seaLevel != null) ((h - seaLevel) / 200.0).coerceIn(0.0, 1.0) else 1.0
            h += detail * detailAmp * landFactor
        }
        if (water) h = seaLevel!!
        return doubleArrayOf(h, if (water) -1.0 else detail.coerceIn(-1.0, 1.0) * 0.5 + 0.5)
    }

    private fun craters(d: Vec3d): Double {
        var sum = 0.0
        var scale = 6.0
        for (oct in 0 until 4) {
            val px = d.x * scale; val py = d.y * scale; val pz = d.z * scale
            val cx = floor(px); val cy = floor(py); val cz = floor(pz)
            val r = Rng(Rng.hash(cx.toLong(), cy.toLong(), cz.toLong(), oct.toLong()))
            val ox = cx + r.nextDouble(); val oy = cy + r.nextDouble(); val oz = cz + r.nextDouble()
            val rad = 0.15 + r.nextDouble() * 0.3
            val dx = px - ox; val dy = py - oy; val dz = pz - oz
            val dist = sqrt(dx * dx + dy * dy + dz * dz) / rad
            if (dist < 1.3) {
                sum += if (dist < 1.0) (dist * dist - 1.0) * 0.8 else (1.3 - dist) * 0.9
            }
            scale *= 2.7
        }
        return sum / 2
    }

    companion object {
        /** Builds the model for a body, or null for worlds without a solid surface. */
        fun forBody(context: Context, body: Body): TerrainModel? {
            when (body) {
                is SolarBody -> {
                    if (body.look.style == 1) return null // gas giants and Venus' clouds: no landing
                    val map = body.look.heights?.let { HeightMap.load(context, it) }
                    val seed = Rng.hashString(body.id)
                    return when (body.id) {
                        "sol:earth" -> TerrainModel(body.id, body.radius, map, seed, 3000.0, 180.0, 0.0, 0)
                        "sol:moon" -> TerrainModel(body.id, body.radius, map, seed, 3000.0, 120.0, null, 1)
                        "sol:mars" -> TerrainModel(body.id, body.radius, map, seed, 4000.0, 160.0, null, 0)
                        else -> {
                            val airless = body.look.atmosphereStrength <= 0f
                            TerrainModel(body.id, body.radius, map, seed, max(500.0, body.radius * 0.002), 120.0,
                                null, if (airless) 1 else if (body.look.style == 2) 2 else 0)
                        }
                    }
                }
                is PlanetBody -> {
                    if (!body.solid) return null
                    val seed = body.seed
                    val sea = if (body.type == PlanetType.OCEAN) 0.0 else null
                    val flavor = when (body.type) {
                        PlanetType.LAVA -> 3
                        PlanetType.ICE -> 2
                        PlanetType.ROCKY -> if (body.hasAtmosphere) 0 else 1
                        else -> 0
                    }
                    return TerrainModel(body.id, body.radius, null, seed, body.radius * 0.0015, 150.0, sea, flavor)
                }
                else -> return null
            }
        }

        // ---- cube-sphere geometry (shared with the renderer)
        val FACE_N = arrayOf(Vec3d(1.0, 0.0, 0.0), Vec3d(-1.0, 0.0, 0.0), Vec3d(0.0, 1.0, 0.0), Vec3d(0.0, -1.0, 0.0), Vec3d(0.0, 0.0, 1.0), Vec3d(0.0, 0.0, -1.0))
        val FACE_A = arrayOf(Vec3d(0.0, 1.0, 0.0), Vec3d(0.0, 0.0, 1.0), Vec3d(0.0, 0.0, 1.0), Vec3d(1.0, 0.0, 0.0), Vec3d(1.0, 0.0, 0.0), Vec3d(0.0, 1.0, 0.0))
        val FACE_B = arrayOf(Vec3d(0.0, 0.0, 1.0), Vec3d(0.0, 1.0, 0.0), Vec3d(1.0, 0.0, 0.0), Vec3d(0.0, 0.0, 1.0), Vec3d(0.0, 1.0, 0.0), Vec3d(1.0, 0.0, 0.0))

        fun cubeDir(face: Int, u: Double, v: Double): Vec3d =
            (FACE_N[face] + FACE_A[face] * u + FACE_B[face] * v).normalized()
    }
}

/** One terrain tile of the cube-sphere quadtree. */
data class TileKey(val face: Int, val level: Int, val x: Int, val y: Int) {
    val size get() = 2.0 / (1 shl level)
    val u0 get() = -1.0 + x * size
    val v0 get() = -1.0 + y * size
    fun centerDir() = TerrainModel.cubeDir(face, u0 + size / 2, v0 + size / 2)
    fun children() = listOf(
        TileKey(face, level + 1, x * 2, y * 2), TileKey(face, level + 1, x * 2 + 1, y * 2),
        TileKey(face, level + 1, x * 2, y * 2 + 1), TileKey(face, level + 1, x * 2 + 1, y * 2 + 1),
    )
}

/** CPU-side mesh for a tile: vertices relative to [centerLocal] (body frame, metres). */
class TileMesh(val key: TileKey, val centerLocal: Vec3d, val vertices: FloatArray, val radiusM: Double)

object TileBuilder {
    const val N = 17 // vertices per side

    /** Builds a tile mesh: 8 floats per vertex (pos xyz, normal xyz, height, detail), plus a skirt. */
    fun build(model: TerrainModel, key: TileKey): TileMesh {
        val g = N + 2 // with a border ring for normals
        val pos = arrayOfNulls<Vec3d>(g * g)
        val hd = Array(g * g) { DoubleArray(2) }
        val step = key.size / (N - 1)
        for (j in 0 until g) for (i in 0 until g) {
            val u = key.u0 + (i - 1) * step
            val v = key.v0 + (j - 1) * step
            val d = TerrainModel.cubeDir(key.face, u, v)
            val h = model.heightAndDetail(d)
            hd[j * g + i] = h
            pos[j * g + i] = d * (model.radius + h[0])
        }
        val center = TerrainModel.cubeDir(key.face, key.u0 + key.size / 2, key.v0 + key.size / 2) *
            (model.radius + hd[(g / 2) * g + g / 2][0])
        val skirtDepth = model.radius * key.size * 0.02 + 5.0
        val out = FloatArray((N * N + 4 * N) * 8)
        var o = 0
        fun put(p: Vec3d, n: Vec3d, h: Double, det: Double) {
            out[o++] = (p.x - center.x).toFloat(); out[o++] = (p.y - center.y).toFloat(); out[o++] = (p.z - center.z).toFloat()
            out[o++] = n.x.toFloat(); out[o++] = n.y.toFloat(); out[o++] = n.z.toFloat()
            out[o++] = h.toFloat(); out[o++] = det.toFloat()
        }
        val normals = arrayOfNulls<Vec3d>(N * N)
        var maxR = 0.0
        for (j in 0 until N) for (i in 0 until N) {
            val gi = j + 1; val gj = i + 1
            val p = pos[gi * g + gj]!!
            val dx = pos[gi * g + gj + 1]!! - pos[gi * g + gj - 1]!!
            val dy = pos[(gi + 1) * g + gj]!! - pos[(gi - 1) * g + gj]!!
            var n = dx.cross(dy).normalized()
            if (n.dot(p) < 0) n = -n
            normals[j * N + i] = n
            val h = hd[gi * g + gj]
            put(p, n, h[0], h[1])
            maxR = max(maxR, (p - center).length())
        }
        // Skirt: copies of the edge vertices pushed inward, hiding cracks between levels.
        val edges = ArrayList<Int>()
        for (i in 0 until N) edges.add(i)                       // bottom row (j = 0)
        for (i in 0 until N) edges.add((N - 1) * N + i)         // top row
        for (j in 0 until N) edges.add(j * N)                   // left column
        for (j in 0 until N) edges.add(j * N + N - 1)           // right column
        for (idx in edges) {
            val j = idx / N; val i = idx % N
            val p = pos[(j + 1) * g + i + 1]!!
            val h = hd[(j + 1) * g + i + 1]
            put(p - p.normalized() * skirtDepth, normals[idx]!!, h[0], h[1])
        }
        return TileMesh(key, center, out, maxR)
    }

    /** Shared index buffer for every tile (grid + skirts). */
    fun indices(): IntArray {
        val idx = ArrayList<Int>()
        for (j in 0 until N - 1) for (i in 0 until N - 1) {
            val a = j * N + i
            val b = a + 1
            val c = a + N
            val d = c + 1
            idx.add(a); idx.add(b); idx.add(d)
            idx.add(a); idx.add(d); idx.add(c)
        }
        val base = N * N
        // bottom edge (j=0): skirt vertices base + i
        for (i in 0 until N - 1) quad(idx, i + 1, i, base + i, base + i + 1)
        // top edge (j=N-1)
        val topRow = (N - 1) * N
        for (i in 0 until N - 1) quad(idx, topRow + i, topRow + i + 1, base + N + i + 1, base + N + i)
        // left edge (i=0)
        for (j in 0 until N - 1) quad(idx, j * N, (j + 1) * N, base + 2 * N + j + 1, base + 2 * N + j)
        // right edge (i=N-1)
        for (j in 0 until N - 1) quad(idx, (j + 1) * N + N - 1, j * N + N - 1, base + 3 * N + j, base + 3 * N + j + 1)
        return idx.toIntArray()
    }

    private fun quad(idx: ArrayList<Int>, a: Int, b: Int, c: Int, d: Int) {
        // Both windings, so skirts are visible from either side.
        idx.add(a); idx.add(b); idx.add(c); idx.add(a); idx.add(c); idx.add(d)
        idx.add(a); idx.add(c); idx.add(b); idx.add(a); idx.add(d); idx.add(c)
    }

    /** Arc length of a tile edge in metres. */
    fun tileSizeM(model: TerrainModel, key: TileKey) = model.radius * (PI / 2) / (1 shl key.level)

    fun earthRadius() = Units.R_EARTH
}
