package com.emotionalleaves.universe.render

import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.engine.Body
import com.emotionalleaves.universe.engine.FieldGalaxies
import com.emotionalleaves.universe.engine.GalaxyBody
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max

/**
 * Generated galaxies around the camera (dwarfs and ordinary galaxies beyond the catalogues), drawn
 * as points and tappable. Once one is close enough to show a shape it joins the galaxy list and
 * the catalogue layer draws it instead.
 */
class FieldGalaxyLayer : Layer {
    private var src: FieldGalaxies? = null
    private var srcList: List<GalaxyBody>? = null
    private var batch: PointBatch? = null
    var daylight = 0f

    private fun exposure(frame: Frame): Float = (7.2 + 5 * log10(max(1.0, frame.focusDistance / (0.3 * Units.MPC)))).toFloat()

    override fun init(rc: RenderContext) {
        src = null; srcList = null; batch = null
    }

    override fun update(frame: Frame, rc: RenderContext) {
        val w = rc.engine.world
        val f = w.field
        val list = w.galaxies
        if (f === src && list === srcList) return
        src = f; srcList = list
        batch?.release(); batch = null
        if (f == null) return
        val data = f.data.copyOf()
        // Registered ones are drawn by the catalogue layer; hide them here so they are not doubled.
        for (i in 0 until f.count) if (w.isRegistered(f.id(i))) data[i * 7 + 6] = 99f
        batch = PointBatch.upload(f.anchor, Units.MPC, data)
    }

    override fun drawTransparent(frame: Frame, rc: RenderContext) {
        val b = batch ?: return
        val day = 1f - daylight
        if (day < 0.03f) return
        rc.drawer.beginAdditive()
        rc.drawer.points(frame, b, exposure(frame), 2.4f, 0.85f * day, minSize = 1.2f, maxSize = 6f)
        rc.drawer.end()
    }

    override fun pickAt(frame: Frame, rc: RenderContext, x: Float, y: Float, reach: Float): Body? {
        val f = src ?: return null
        val exp = exposure(frame)
        var best = -1
        var bestScore = Double.MAX_VALUE
        for (i in 0 until f.count) {
            val rel = frame.rel(f.position(i))
            val dist = rel.length()
            val m = f.data[i * 7 + 6] + 5 * log10(max(dist / Units.PC, 1.0)) - 5
            if (m > exp + 0.5) continue
            val s = frame.project(rel) ?: continue
            val px = hypot((s.first - x).toDouble(), (s.second - y).toDouble())
            if (px > reach) continue
            val score = px + (m - exp) * 2.0
            if (score < bestScore) { bestScore = score; best = i }
        }
        if (best < 0) return null
        return rc.engine.world.galaxyById(f.id(best))
    }
}
