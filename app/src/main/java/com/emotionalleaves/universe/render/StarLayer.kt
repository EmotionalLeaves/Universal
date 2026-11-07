package com.emotionalleaves.universe.render

import android.opengl.GLES30
import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.smoothstep
import com.emotionalleaves.universe.engine.Body
import com.emotionalleaves.universe.engine.RealStars
import com.emotionalleaves.universe.engine.StarCatalog
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max

/** Real catalogue stars as points with true colours and brightness, plus constellation lines. */
class StarLayer : Layer {
    private class Uploaded(val catalog: StarCatalog, val batches: List<PointBatch>)

    private val uploaded = ArrayList<Uploaded>()
    private var constellationMesh: LineMesh? = null
    private var stars: RealStars? = null
    private var fallback: PointBatch? = null
    var magLimit = 7.2f
    /** 0..1: how much daylight washes out the stars (from the terrain layer). */
    var daylight = 0f

    override fun init(rc: RenderContext) {
        uploaded.clear()
        constellationMesh = null
        stars = null
        fallback = null
    }

    override fun update(frame: Frame, rc: RenderContext) {
        val rs = rc.engine.world.realStars
        if (rs == null) {
            if (fallback == null && rc.engine.world.starsFailed) fallback = TestLayer.backgroundStars()
            return
        }
        if (stars !== rs) {
            stars = rs
            for (u in uploaded) u.batches.forEach { it.release() }
            uploaded.clear()
            constellationMesh?.release()
            constellationMesh = buildConstellations(rs)
        }
        // Upload any catalogues (base or newly downloaded packs) not on the GPU yet.
        for (c in rs.allCatalogs()) {
            if (uploaded.any { it.catalog === c }) continue
            uploaded.add(Uploaded(c, mergeTiles(c)))
            break // one catalogue per frame
        }
        // Drop catalogues whose pack was deleted.
        val live = rs.allCatalogs()
        val gone = uploaded.filter { u -> live.none { it === u.catalog } }
        for (g in gone) { g.batches.forEach { it.release() }; uploaded.remove(g) }
    }

    override fun drawTransparent(frame: Frame, rc: RenderContext) {
        val lim = magLimit + rc.engine.settings.starBrightness - daylight * 7f
        if (daylight > 0.97f) return
        rc.drawer.beginAdditive()
        fallback?.let { rc.drawer.points(frame, it, lim, 3f, 1f, minSize = 1.5f, maxSize = 9f) }
        for (u in uploaded) for (b in u.batches) {
            rc.drawer.points(frame, b, lim, 3.2f, 1f, minSize = 1.3f, maxSize = 13f)
        }
        val cm = constellationMesh
        if (cm != null && rc.engine.settings.showConstellations) {
            val fromSun = (frame.camPos - UPos.ZERO).length()
            val a = (0.35 * (1.0 - smoothstep(30 * Units.PC, 300 * Units.PC, fromSun))).toFloat()
            if (a > 0.01) {
                rc.drawer.lines(frame, cm, frame.rel(UPos.ZERO), Units.PC, Quatd.IDENTITY, floatArrayOf(0.45f, 0.65f, 1f), a)
            }
        }
        rc.drawer.end()
    }

    override fun collect(frame: Frame, rc: RenderContext, out: MutableList<Pickable>) {
        val rs = stars ?: return
        // Label the brightest named stars as seen from the camera.
        val cands = ArrayList<Pair<Int, Double>>()
        for (id in rs.namedIds) {
            val (tile, i) = rs.base.locate(id) ?: continue
            val p = UPos.ZERO + tile.posPc(i) * Units.PC
            val d = (p - frame.camPos).length()
            if (d < Units.AU) continue
            val m = tile.absMag(i) + 5 * log10(d / Units.PC) - 5
            if (m < 3.2 + rc.engine.settings.starBrightness) cands.add(id to m)
        }
        cands.sortBy { it.second }
        var shown = 0
        for ((id, m) in cands) {
            if (shown >= 30) break
            val (tile, i) = rs.base.locate(id) ?: continue
            val p = UPos.ZERO + tile.posPc(i) * Units.PC
            val s = frame.project(frame.rel(p)) ?: continue
            if (s.first < 0 || s.second < 0 || s.first > frame.width || s.second > frame.height) continue
            val body = rs.bodyFrom(tile, i)
            out.add(Pickable(body, s.first, s.second, 6f, (3.5 - m).toFloat().coerceAtLeast(0.5f), true, 0xFFB8C8E8.toInt()))
            shown++
        }
        val sel = rc.engine.selected
        if (sel != null && sel.id.startsWith("hyg:") && out.none { it.body == sel }) {
            val s = frame.project(frame.rel(sel.position(frame.jd)))
            if (s != null) out.add(Pickable(sel, s.first, s.second, 6f, 5f, true))
        }
    }

    /** Finds the star nearest a tap, preferring brighter ones. */
    override fun pickAt(frame: Frame, rc: RenderContext, x: Float, y: Float, reach: Float): Body? {
        val rs = stars ?: return null
        val lim = magLimit + rc.engine.settings.starBrightness
        var best: Pair<com.emotionalleaves.universe.engine.StarTile, Int>? = null
        var bestScore = Double.MAX_VALUE
        for (c in rs.allCatalogs()) for (tile in c.tiles) {
            val d = tile.data
            for (i in 0 until tile.count) {
                val p = UPos.ZERO + tile.posPc(i) * Units.PC
                val rel = frame.rel(p)
                val dist = rel.length()
                if (dist < 1e9) continue
                val m = d[i * 7 + 6] + 5 * log10(dist / Units.PC) - 5
                if (m > lim + 0.5) continue
                val s = frame.project(rel) ?: continue
                val px = hypot((s.first - x).toDouble(), (s.second - y).toDouble())
                if (px > reach) continue
                val score = px + m * 3.0
                if (score < bestScore) { bestScore = score; best = tile to i }
            }
        }
        val b = best ?: return null
        return rs.bodyFrom(b.first, b.second)
    }

    /**
     * Merges the 200 pc catalogue tiles into ~1,600 pc super-tiles so a catalogue costs tens of draw
     * calls instead of thousands (offsets stay small enough for float precision).
     */
    private fun mergeTiles(c: StarCatalog): List<PointBatch> {
        val groups = LinkedHashMap<Triple<Int, Int, Int>, MutableList<com.emotionalleaves.universe.engine.StarTile>>()
        val size = 1600.0
        for (t in c.tiles) {
            val k = Triple(kotlin.math.floor(t.anchorPc.x / size).toInt(), kotlin.math.floor(t.anchorPc.y / size).toInt(), kotlin.math.floor(t.anchorPc.z / size).toInt())
            groups.getOrPut(k) { ArrayList() }.add(t)
        }
        return groups.map { (k, tiles) ->
            val anchor = com.emotionalleaves.universe.core.Vec3d((k.first + 0.5) * size, (k.second + 0.5) * size, (k.third + 0.5) * size)
            val total = tiles.sumOf { it.count }
            val data = FloatArray(total * 7)
            var o = 0
            for (t in tiles) {
                val dx = (t.anchorPc.x - anchor.x).toFloat()
                val dy = (t.anchorPc.y - anchor.y).toFloat()
                val dz = (t.anchorPc.z - anchor.z).toFloat()
                for (i in 0 until t.count) {
                    data[o] = t.data[i * 7] + dx; data[o + 1] = t.data[i * 7 + 1] + dy; data[o + 2] = t.data[i * 7 + 2] + dz
                    data[o + 3] = t.data[i * 7 + 3]; data[o + 4] = t.data[i * 7 + 4]; data[o + 5] = t.data[i * 7 + 5]
                    data[o + 6] = t.data[i * 7 + 6]
                    o += 7
                }
            }
            PointBatch.upload(UPos.ZERO + anchor * Units.PC, Units.PC, data)
        }
    }

    private fun buildConstellations(rs: RealStars): LineMesh? {
        val pts = ArrayList<Float>()
        for (c in rs.constellations) {
            for (id in c.pairIds) {
                val (tile, i) = rs.base.locate(id) ?: continue
                val p = tile.posPc(i)
                pts.add(p.x.toFloat()); pts.add(p.y.toFloat()); pts.add(p.z.toFloat())
            }
        }
        if (pts.size < 6) return null
        val n = pts.size / 6 * 6
        return LineMesh.upload(pts.subList(0, n).toFloatArray(), GLES30.GL_LINES)
    }

    companion object {
        fun maxReach(px: Float) = max(px, 30f)
    }
}
