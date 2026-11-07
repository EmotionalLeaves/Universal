package com.emotionalleaves.universe.render

import com.emotionalleaves.universe.core.Rng
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.smoothstep
import com.emotionalleaves.universe.engine.Body
import com.emotionalleaves.universe.engine.GalaxyBody
import com.emotionalleaves.universe.engine.GeneratedStars
import kotlin.math.hypot
import kotlin.math.log10

/**
 * Galaxies seen from inside or nearby: a glow of light sampled from the galaxy's density model,
 * plus individually generated stars around the camera (which never replace real stars).
 */
class GalaxyLayer : Layer {
    private class Glow(val galaxy: GalaxyBody, val batch: PointBatch, val particleMag: Float)

    private val glows = HashMap<String, Glow>()
    private var genBatch: PointBatch? = null
    private var genSource: GeneratedStars? = null
    var magLimit = 7.2f
    var daylight = 0f
    var particles = 40_000

    override fun init(rc: RenderContext) {
        glows.clear()
        genBatch = null
        genSource = null
    }

    /** Builds the glow particle cloud for a galaxy (world orientation, pc relative to its centre). */
    private fun buildGlow(g: GalaxyBody): Glow {
        val n = if (g.isMilkyWay) particles else particles / 3
        val rng = Rng(g.seed * 31 + 5)
        val data = FloatArray(n * 7)
        for (i in 0 until n) {
            val (p, youth) = g.model.sampleLight(rng)
            val w = g.frame.rotate(p)
            data[i * 7] = w.x.toFloat(); data[i * 7 + 1] = w.y.toFloat(); data[i * 7 + 2] = w.z.toFloat()
            val y = youth.toFloat()
            data[i * 7 + 3] = 1.0f - 0.35f * y
            data[i * 7 + 4] = 0.86f - 0.04f * y
            data[i * 7 + 5] = 0.68f + 0.32f * y
            data[i * 7 + 6] = 0f
        }
        val mag = (g.absMag + 2.5 * log10(n.toDouble())).toFloat()
        for (i in 0 until n) data[i * 7 + 6] = mag
        return Glow(g, PointBatch.upload(g.center, Units.PC, data), mag)
    }

    override fun update(frame: Frame, rc: RenderContext) {
        val world = rc.engine.world
        // Keep glow clouds for galaxies within ~40 radii of the camera (at most a few).
        val near = world.nearGalaxies.map { it to 0.0 }
        var built = 0
        for ((g, _) in near) {
            if (!glows.containsKey(g.id) && built == 0) {
                glows[g.id] = buildGlow(g)
                built++
            }
        }
        val keep = near.map { it.first.id }.toSet()
        val drop = glows.keys.filter { it !in keep }
        for (k in drop) { glows[k]?.batch?.release(); glows.remove(k) }

        val gs = world.generated
        if (gs !== genSource) {
            genBatch?.release()
            genBatch = gs?.let { PointBatch.upload(it.anchor, Units.PC, it.data) }
            genSource = gs
        }
    }

    override fun drawTransparent(frame: Frame, rc: RenderContext) {
        rc.drawer.beginAdditive()
        val day = 1f - daylight
        for (glow in glows.values) {
            val g = glow.galaxy
            val dist = (g.center - frame.camPos).length()
            // Seen from far away the galaxy is drawn by the galaxy-catalogue layer instead.
            val fade = (1.0 - smoothstep(g.radius * 12, g.radius * 30, dist)).toFloat()
            if (fade <= 0.01f) continue
            rc.drawer.points(
                frame, glow.batch, magLimit, 3f, fade * day, fadeNear = 700 * Units.PC,
                minSize = 1.5f, maxSize = 64f, worldSize = g.radius * 0.012, glowGain = 6f,
            )
        }
        genBatch?.let {
            if (day > 0.03f) rc.drawer.points(frame, it, magLimit + rc.engine.settings.starBrightness - daylight * 7f, 3.2f, day, minSize = 1.3f, maxSize = 13f)
        }
        rc.drawer.end()
    }

    override fun pickAt(frame: Frame, rc: RenderContext, x: Float, y: Float, reach: Float): Body? {
        val gs = genSource ?: return null
        val lim = magLimit + rc.engine.settings.starBrightness
        var best = -1
        var bestScore = Double.MAX_VALUE
        val d = gs.data
        for (i in 0 until gs.count) {
            val p = gs.anchor + com.emotionalleaves.universe.core.Vec3d(d[i * 7].toDouble(), d[i * 7 + 1].toDouble(), d[i * 7 + 2].toDouble()) * Units.PC
            val rel = frame.rel(p)
            val dist = rel.length()
            if (dist < 1e9) continue
            val m = d[i * 7 + 6] + 5 * log10(dist / Units.PC) - 5
            if (m > lim + 0.5) continue
            val s = frame.project(rel) ?: continue
            val px = hypot((s.first - x).toDouble(), (s.second - y).toDouble())
            if (px > reach) continue
            val score = px + m * 3.0
            if (score < bestScore) { bestScore = score; best = i }
        }
        return if (best >= 0) gs.body(best) else null
    }

    override fun collect(frame: Frame, rc: RenderContext, out: MutableList<Pickable>) {
        val sel = rc.engine.selected
        if (sel != null && sel.id.startsWith("gs:") && out.none { it.body == sel }) {
            frame.project(frame.rel(sel.position(frame.jd)))?.let { out.add(Pickable(sel, it.first, it.second, 6f, 5f, true)) }
        }
        // Label the galaxy you are in when seen from outside it.
        val mw = rc.engine.world.milkyWay
        val d = (mw.center - frame.camPos).length()
        if (d > mw.radius * 1.2) {
            frame.project(frame.rel(mw.center))?.let { s ->
                out.add(Pickable(mw, s.first, s.second, frame.pixelSize(mw.radius, d).toFloat() * 0.3f, 8f, true, 0xFFE8DCC8.toInt()))
            }
        }
    }
}
