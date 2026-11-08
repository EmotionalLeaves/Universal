package com.emotionalleaves.universe.render

import com.emotionalleaves.universe.core.UPos

import com.emotionalleaves.universe.engine.GalaxyType

import kotlin.math.abs

import kotlin.math.exp

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
    private class Glow(val galaxy: GalaxyBody, val batch: PointBatch, val particleMag: Float) { var inside = true }

    private val glows = HashMap<String, Glow>()
    private var genBatch: PointBatch? = null
    private var genSource: GeneratedStars? = null
    var magLimit = 7.2f
    var daylight = 0f
    var particles = 40_000
    /** Largest glow blob in pixels; big blobs cost a lot of GPU fill (Low 14, Medium 20, High 28). */
    var maxBlob = 20f

    override fun init(rc: RenderContext) {
        glows.clear()
        ready.clear()
        building.clear()
        genBatch = null
        genSource = null
    }

    /** Particle clouds are computed on a worker (tens of thousands of samples) and uploaded here. */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val ready = java.util.concurrent.ConcurrentLinkedQueue<Triple<GalaxyBody, FloatArray, Boolean>>()
    private val building = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var insideMw = true

    /** Builds the glow particle data for a galaxy (world orientation, pc relative to its centre). */
    private fun glowData(g: GalaxyBody, particles: Int, fromInside: Boolean): FloatArray {
        val n = if (g.isMilkyWay) particles else particles / 3
        val rng = Rng(g.seed * 31 + 5)
        val data = FloatArray(n * 7)
        val mag = (g.absMag + 2.5 * log10(n.toDouble())).toFloat()
        val m = g.model
        val dusty = m.type != GalaxyType.ELLIPTICAL
        // Where the Sun sits in this galaxy's frame (only matters for the Milky Way).
        val sun = if (g.isMilkyWay && fromInside) g.frame.conjugate().rotate((UPos.ZERO - g.center) / Units.PC) else null
        for (i in 0 until n) {
            val (p, youth) = m.sampleLight(rng)
            val w = g.frame.rotate(p)
            data[i * 7] = w.x.toFloat(); data[i * 7 + 1] = w.y.toFloat(); data[i * 7 + 2] = w.z.toFloat()
            // Dust: a dark lane along the disk's mid-plane (edge-on galaxies), and for the Milky Way
            // seen from the Sun, the long dusty path through the disk. Without it the centre of our
            // galaxy was a flat white slab; with it, the band has its familiar dark rift.
            var dust = 0.0
            if (dusty) dust += 0.9 * exp(-abs(p.z) / (m.hz * 0.7))
            if (sun != null) {
                val path = kotlin.math.hypot(p.x - sun.x, p.y - sun.y)
                val zMid = abs(p.z + sun.z) * 0.5
                dust += 2.8 * (path / 3000.0).coerceAtMost(1.6) * exp(-zMid / 260.0)
            }
            val red = (dust * 0.12).coerceAtMost(0.35).toFloat()
            val y = youth.toFloat()
            data[i * 7 + 3] = 1.0f - 0.35f * y
            data[i * 7 + 4] = (0.86f - 0.04f * y) * (1f - red * 0.6f)
            data[i * 7 + 5] = (0.68f + 0.32f * y) * (1f - red)
            // A little less light in the bulge and a little more in young arm stars: arms read better
            // (the core still glows, without swallowing the spiral).
            val tone = if (youth < 0.1) 0.7 else -0.35 * (youth - 0.3)
            data[i * 7 + 6] = mag + dust.toFloat() + tone.toFloat()
        }
        return data
    }

    override fun update(frame: Frame, rc: RenderContext) {
        val world = rc.engine.world
        // Keep glow clouds for galaxies within ~40 radii of the camera (at most a few).
        val near = world.nearGalaxies.map { it to 0.0 }
        while (true) {
            val (g, data, builtInside) = ready.poll() ?: break
            building.remove(g.id)
            if (!glows.containsKey(g.id)) glows[g.id] = Glow(g, PointBatch.upload(g.center, Units.PC, data), (g.absMag + 2.5 * log10((data.size / 7).toDouble())).toFloat()).also { it.inside = builtInside }
        }
        // The Milky Way's dust is laid out as seen from the Sun; seen from outside it is rebuilt without it.
        world.galaxies.firstOrNull { it.isMilkyWay }?.let { mw ->
            insideMw = (frame.camPos - mw.center).length() < mw.radius * 1.3
            val gl = glows[mw.id]
            if (gl != null && gl.inside != insideMw && !building.contains(mw.id)) { gl.batch.release(); glows.remove(mw.id) }
        }
        for ((g, _) in near) {
            if (!glows.containsKey(g.id) && building.add(g.id)) {
                val n = particles
                val inside = insideMw
                worker.execute {
                    try { ready.add(Triple(g, glowData(g, n, inside), inside)) } catch (_: Throwable) { building.remove(g.id) }
                }
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
            // Looking through the disk from inside piles up many blobs; from outside far fewer
            // overlap, so boost the gain with distance to keep the galaxy visible as a whole.
            val boost = ((dist / (0.5 * g.radius)) * (dist / (0.5 * g.radius))).coerceIn(1.0, 6.0)
            // Never brighter than an even glow over the bright half of the galaxy: thousands of blobs
            // piled into the core burned it to a flat white disc.
            val pxPerRad = frame.height / frame.fovY
            val n = glow.batch.count.coerceAtLeast(1)
            val halfPx = 0.35 * g.radius / dist * pxPerRad
            val blobPx = (g.radius * 0.012 / dist * pxPerRad).coerceIn(1.5, maxBlob.toDouble())
            // The bulge holds ~a quarter of the light inside a few hundred px²: cap so it stays just under white.
            val corePx = (g.model.bulgeScale * Units.PC * 1.6 / dist * pxPerRad).coerceAtLeast(blobPx)
            val share = if (g.model.type == GalaxyType.ELLIPTICAL) 1.0 else 0.3
            val cap = kotlin.math.min(1.4 * Math.PI * halfPx * halfPx / n, 2.4 * Math.PI * corePx * corePx / (n * share))
            // Particle brightness at the galaxy's distance, relative to the usual exposure.
            val dm = 5 * log10(kotlin.math.max(dist / Units.PC, 1.0)) - 5
            val lim = (glow.particleMag + dm).toFloat()
            val wanted = 2.2 * boost * Math.pow(10.0, -0.4 * (lim - magLimit))
            val gain = kotlin.math.min(wanted, cap).toFloat()
            rc.drawer.points(
                frame, glow.batch, lim, 3f, fade * day, fadeNear = 700 * Units.PC,
                minSize = 1.5f, maxSize = maxBlob, worldSize = g.radius * 0.012, glowGain = gain,
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
