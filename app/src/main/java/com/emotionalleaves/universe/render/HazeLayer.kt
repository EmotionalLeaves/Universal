package com.emotionalleaves.universe.render

import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.smoothstep
import com.emotionalleaves.universe.engine.GalaxyBody
import com.emotionalleaves.universe.engine.GalaxyType
import com.emotionalleaves.universe.engine.Source
import kotlin.math.pow

/**
 * Makes zoomed-out views glow instead of looking black: every real catalogue galaxy adds a soft
 * halo (so the Virgo cluster, the Local Sheet and other real structures light up), and beyond the
 * catalogues a generated haze traces the cosmic web's filaments and clusters.
 */
class HazeLayer : Layer {
    private var farSrc: FloatArray? = null
    private var far: PointBatch? = null
    private var localFor: GalaxyBody? = null
    private var local: PointBatch? = null
    var daylight = 0f
    private var deep: com.emotionalleaves.universe.engine.DeepHaze.Result? = null
    private var deepBatch: PointBatch? = null
    @Volatile private var deepPending: com.emotionalleaves.universe.engine.DeepHaze.Result? = null
    @Volatile private var deepBusy = false
    private var deepWant: Pair<Int, Triple<Long, Long, Long>>? = null
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun init(rc: RenderContext) { farSrc = null; far = null; localFor = null; local = null; deep = null; deepBatch = null }

    /** Keeps the deep haze matched to the zoom level and camera cell (only with "beyond" on). */
    private fun updateDeep(frame: Frame, rc: RenderContext) {
        deepPending?.let { r ->
            deepBatch?.release()
            deepBatch = if (r.data.isNotEmpty()) PointBatch.upload(r.anchor, Units.MPC, r.data) else null
            deep = r
            deepPending = null
        }
        if (rc.engine.world.outerWeb == null) { deepBatch?.release(); deepBatch = null; deep = null; deepWant = null; return }
        if (frame.focusDistance < 2 * Units.GLY) return
        val level = com.emotionalleaves.universe.engine.DeepHaze.levelFor(frame.focusDistance)
        val cell = com.emotionalleaves.universe.engine.DeepHaze.cellOf(frame.camPos, level)
        val want = level to cell
        if (want == deepWant || deepBusy) return
        deepWant = want
        deepBusy = true
        worker.execute {
            try { deepPending = com.emotionalleaves.universe.engine.DeepHaze.generate(level, cell) } catch (_: Throwable) {} finally { deepBusy = false }
        }
    }

    override fun update(frame: Frame, rc: RenderContext) {
        updateDeep(frame, rc)
        val w = rc.engine.world
        val h = w.haze
        if (h !== farSrc) {
            far?.release()
            far = h?.let { PointBatch.upload(UPos.ZERO, Units.MPC, it) }
            farSrc = h
        }
        // Real galaxies: rebuild when the catalogue itself changes (its second entry identifies it).
        val list = w.galaxies
        val key = list.getOrNull(1)
        if (key != null && key !== localFor && list.size > 1000) {
            localFor = key
            local?.release()
            val real = list.filter { it.source == Source.REAL && !it.isMilkyWay }
            val d = FloatArray(real.size * 7)
            for ((i, g) in real.withIndex()) {
                val p = (g.center - UPos.ZERO) / Units.MPC
                val warm = g.model.type == GalaxyType.ELLIPTICAL || g.model.type == GalaxyType.LENTICULAR
                d[i * 7] = p.x.toFloat(); d[i * 7 + 1] = p.y.toFloat(); d[i * 7 + 2] = p.z.toFloat()
                d[i * 7 + 3] = if (warm) 1f else 0.62f
                d[i * 7 + 4] = if (warm) 0.82f else 0.72f
                d[i * 7 + 5] = if (warm) 0.62f else 1f
                d[i * 7 + 6] = (10.0.pow(-0.4 * (g.absMag + 20.5))).coerceIn(0.2, 1.0).toFloat()
            }
            local = PointBatch.upload(UPos.ZERO, Units.MPC, d)
        }
    }

    override fun drawTransparent(frame: Frame, rc: RenderContext) {
        val day = 1f - daylight
        // Only once you have zoomed out past our galaxy's neighbourhood.
        val vis = (smoothstep(3 * Units.MPC, 30 * Units.MPC, frame.focusDistance) * day).toFloat()
        if (vis < 0.01f) return
        rc.drawer.beginAdditive()
        local?.let { rc.drawer.haze(frame, it, 1.6 * Units.MPC, 0.30f * vis, 1.5 * Units.MPC, minSize = 2f, maxSize = 36f) }
        far?.let { rc.drawer.haze(frame, it, 22 * Units.MPC, 0.11f * vis, 25 * Units.MPC, minSize = 2.5f, maxSize = 48f) }
        val d = deep
        val db = deepBatch
        if (d != null && db != null) rc.drawer.haze(frame, db, d.blobMpc * Units.MPC, 0.09f * vis, 0.0, minSize = 2.5f, maxSize = 56f)
        rc.drawer.end()
    }
}
