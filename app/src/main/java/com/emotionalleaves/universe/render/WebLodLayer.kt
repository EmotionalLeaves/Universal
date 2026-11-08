package com.emotionalleaves.universe.render

import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import com.emotionalleaves.universe.core.smoothstep
import com.emotionalleaves.universe.engine.WebLod

/**
 * The cosmic web around whatever you are looking at, at the scale you are looking at it
 * (see [WebLod]). Fills the space between field galaxies (a few Mpc) and the whole-universe web.
 */
class WebLodLayer : Layer {
    var daylight = 0f
    private var batch: PointBatch? = null
    @Volatile private var pending: WebLod.Result? = null
    @Volatile private var busy = false
    private var want: Any? = null
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun init(rc: RenderContext) { batch = null; want = null }

    override fun update(frame: Frame, rc: RenderContext) {
        pending?.let { r ->
            batch?.release()
            batch = if (r.data.isNotEmpty()) PointBatch.upload(r.anchor, Units.MPC, r.data) else null
            pending = null
        }
        if (frame.focusDistance < 4 * Units.MPC || frame.focusDistance > 4 * Units.GLY * 3.26) return
        val focus = frame.camPos + frame.orientation.rotate(Vec3d(0.0, 0.0, -1.0)) * frame.focusDistance
        val level = WebLod.levelFor(frame.focusDistance)
        val cell = WebLod.cellOf(focus, level)
        val inner = rc.engine.world.cosmicWeb?.innerMpc ?: 300.0
        val w = Triple(level, cell, inner)
        if (w == want || busy) return
        want = w
        busy = true
        worker.execute {
            try { pending = WebLod.generate(level, cell, inner) } catch (_: Throwable) {} finally { busy = false }
        }
    }

    override fun drawTransparent(frame: Frame, rc: RenderContext) {
        val b = batch ?: return
        val day = 1f - daylight
        // Hand over to field galaxies close up and to the whole-universe web far out.
        val vis = (smoothstep(4 * Units.MPC, 12 * Units.MPC, frame.focusDistance) *
            (1.0 - smoothstep(1.5 * Units.GLY * 3.26, 4 * Units.GLY * 3.26, frame.focusDistance)) * day).toFloat()
        if (vis < 0.01f) return
        rc.drawer.beginAdditive()
        rc.drawer.points(frame, b, Exposure.of(frame), 2.2f, 0.9f * vis, minSize = 1.3f, maxSize = 5f)
        rc.drawer.end()
    }
}
