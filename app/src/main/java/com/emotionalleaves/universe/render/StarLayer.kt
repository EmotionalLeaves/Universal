package com.emotionalleaves.universe.render

import com.emotionalleaves.universe.engine.Constellation

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
    private var consFor: List<Constellation>? = null
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
        preparing.clear(); prepared.clear(); staging = null
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
            preparing.clear(); prepared.clear(); staging = null
            constellationMesh?.release()
            constellationMesh = buildConstellations(rs)
            consFor = rs.constellations
        }
        // Constellation figures arrive a little after the stars (names are parsed second).
        if (consFor !== rs.constellations) {
            consFor = rs.constellations
            constellationMesh?.release()
            constellationMesh = buildConstellations(rs)
        }
        // Catalogues (base, downloaded packs, streamed Gaia cells) not on the GPU yet are merged and
        // indexed on a background thread, then uploaded a slice per frame: no frozen frames.
        val live = rs.allCatalogs()
        for (c in live) {
            if (uploaded.any { it.catalog === c } || preparing.contains(c)) continue
            preparing.add(c)
            ChunkedLoader.worker.execute {
                val parts = try { com.emotionalleaves.universe.core.Heavy.run(3) { prepare(c) } } catch (e: Throwable) { com.emotionalleaves.universe.engine.ErrorLog.record("preparing stars", e); emptyList() }
                prepared.add(c to parts)
            }
        }
        var budget = 150_000
        while (budget > 0) {
            if (staging == null) {
                val (c, parts) = prepared.poll() ?: break
                staging = Staging(c, parts.map { p ->
                    val d = p.data
                    PointBatch.Staged(UPos.ZERO + p.anchor * Units.PC, Units.PC, d.size / 7, p.index) { i, out, at -> System.arraycopy(d, i * 7, out, at, 7) }
                })
            }
            val st = staging!!
            while (budget > 0 && st.done.size < st.parts.size) {
                val part = st.parts[st.done.size]
                val b = part.step(budget)
                budget -= part.count.coerceAtMost(budget)
                if (b == null) break
                st.done.add(b)
            }
            if (st.done.size < st.parts.size) break
            preparing.remove(st.catalog)
            if (live.any { it === st.catalog } && stars === rs && uploaded.none { it.catalog === st.catalog }) uploaded.add(Uploaded(st.catalog, st.done))
            else st.done.forEach { it.release() }
            staging = null
        }
        // Drop catalogues whose pack was deleted.
        val gone = uploaded.filter { u -> live.none { it === u.catalog } }
        for (g in gone) { g.batches.forEach { it.release() }; uploaded.remove(g) }
    }

    override fun drawTransparent(frame: Frame, rc: RenderContext) {
        val lim = magLimit + rc.engine.settings.starBrightness - daylight * 7f
        if (daylight > 0.97f) return
        rc.drawer.beginAdditive()
        fallback?.let { rc.drawer.points(frame, it, lim, 3f, 1f, minSize = 1.3f, maxSize = 9f) }
        // From far outside the galaxy individual stars are invisible: skip ~110,000+ vertices.
        val fromSun = (frame.camPos - UPos.ZERO).length()
        if (fromSun < 300_000 * Units.LY) {
            for (u in uploaded) for (b in u.batches) {
                rc.drawer.points(frame, b, lim, 3.2f, 1f, minSize = 1.3f, maxSize = 13f)
            }
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

    private var candAge = 0
    private var candFor: RealStars? = null
    private var labelCands: List<Triple<Body, UPos, Double>> = emptyList()
    private val labelBodies = HashMap<Int, Body>()

    override fun collect(frame: Frame, rc: RenderContext, out: MutableList<Pickable>) {
        val rs = stars ?: return
        // Label the brightest named stars as seen from the camera. Which ones those are changes slowly,
        // so the list is worked out a few times a second and only their screen positions every frame.
        if (candAge++ % 12 == 0 || candFor !== rs) {
            candFor = rs
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
            labelCands = cands.take(90).mapNotNull { (id, m) ->
                val (tile, i) = rs.base.locate(id) ?: return@mapNotNull null
                Triple(labelBodies.getOrPut(id) { rs.bodyFrom(tile, i) }, UPos.ZERO + tile.posPc(i) * Units.PC, m)
            }
            if (labelBodies.size > 2000) labelBodies.clear()
        }
        var shown = 0
        for ((body, p, m) in labelCands) {
            if (shown >= 30) break
            val s = frame.project(frame.rel(p)) ?: continue
            if (s.first < 0 || s.second < 0 || s.first > frame.width || s.second > frame.height) continue
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
        // From far outside the galaxy the stars aren't drawn, so they can't be tapped either.
        if ((frame.camPos - UPos.ZERO).length() >= 300_000 * Units.LY) return null
        val lim = magLimit + rc.engine.settings.starBrightness
        var best: Pair<com.emotionalleaves.universe.engine.StarTile, Int>? = null
        var bestScore = Double.MAX_VALUE
        // Plain arithmetic per star and a cheap "near the tap?" test first: this runs over millions of
        // stars on every tap, and building objects for each one froze the picture for a moment.
        val camPc = (frame.camPos - UPos.ZERO) / Units.PC
        val cone = frame.tapCone(x, y, reach)
        val minPc = 1e9 / Units.PC
        for (c in rs.allCatalogs()) for (tile in c.tiles) {
            val d = tile.data
            val ax = tile.anchorPc.x - camPc.x; val ay = tile.anchorPc.y - camPc.y; val az = tile.anchorPc.z - camPc.z
            for (i in 0 until tile.count) {
                val dx = d[i * 7] + ax; val dy = d[i * 7 + 1] + ay; val dz = d[i * 7 + 2] + az
                val ct = dx * cone[0] + dy * cone[1] + dz * cone[2]
                if (ct <= 0) continue
                val d2 = dx * dx + dy * dy + dz * dz
                if (d2 - ct * ct > cone[3] * ct * ct || d2 < minPc * minPc) continue
                val m = d[i * 7 + 6] + 5 * log10(kotlin.math.sqrt(d2)) - 5
                if (m > lim + 0.5) continue
                val s = frame.project(com.emotionalleaves.universe.core.Vec3d(dx, dy, dz) * Units.PC) ?: continue
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
    private class Part(val anchor: com.emotionalleaves.universe.core.Vec3d, val data: FloatArray, val index: com.emotionalleaves.universe.engine.ChunkIndex)
    private class Staging(val catalog: StarCatalog, val parts: List<PointBatch.Staged>, val done: MutableList<PointBatch> = ArrayList())

    private val preparing: MutableSet<StarCatalog> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
    private val prepared = java.util.concurrent.ConcurrentLinkedQueue<Pair<StarCatalog, List<Part>>>()
    private var staging: Staging? = null

    /** Merges a catalogue's tiles into 1,600 pc blocks and indexes each for culling (background thread). */
    private fun prepare(c: StarCatalog): List<Part> {
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
            Part(anchor, data, com.emotionalleaves.universe.engine.ChunkIndex.build(total, 2048, { data[it * 7] }, { data[it * 7 + 1] }, { data[it * 7 + 2] }, { data[it * 7 + 6] }))
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
