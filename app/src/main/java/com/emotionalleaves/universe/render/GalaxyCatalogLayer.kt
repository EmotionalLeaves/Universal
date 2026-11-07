package com.emotionalleaves.universe.render

import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import com.emotionalleaves.universe.core.smoothstep
import com.emotionalleaves.universe.engine.Body
import com.emotionalleaves.universe.engine.GalaxyBody
import com.emotionalleaves.universe.engine.GalaxyType
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Every catalogued galaxy: a point when far away, a shaped disc (spiral arms, bulge...) when it
 * covers a few pixels. Brightness is scaled with how far out you are zoomed, like a camera exposure.
 */
class GalaxyCatalogLayer : Layer {
    private var source: List<GalaxyBody>? = null
    private var batch: PointBatch? = null
    private var px = DoubleArray(0)
    private var py = DoubleArray(0)
    private var pz = DoubleArray(0)
    private var pr = DoubleArray(0)
    var daylight = 0f

    override fun init(rc: RenderContext) {
        source = null
        batch = null
    }

    /** Limiting magnitude grows as you zoom out so distant galaxies stay visible. */
    fun exposure(frame: Frame): Float = (7.2 + 5 * log10(max(1.0, frame.focusDistance / (0.3 * Units.MPC)))).toFloat()

    override fun update(frame: Frame, rc: RenderContext) {
        val list = rc.engine.world.galaxies
        if (list === source) return
        source = list
        batch?.release()
        val n = list.size
        val data = FloatArray(n * 7)
        px = DoubleArray(n); py = DoubleArray(n); pz = DoubleArray(n); pr = DoubleArray(n)
        for ((i, g) in list.withIndex()) {
            val v = (g.center - UPos.ZERO) / Units.PC
            px[i] = v.x; py[i] = v.y; pz[i] = v.z; pr[i] = g.radiusPc
            data[i * 7] = v.x.toFloat(); data[i * 7 + 1] = v.y.toFloat(); data[i * 7 + 2] = v.z.toFloat()
            val c = tint(g.model.type)
            data[i * 7 + 3] = c[0]; data[i * 7 + 4] = c[1]; data[i * 7 + 5] = c[2]
            data[i * 7 + 6] = g.absMag.toFloat()
        }
        batch = PointBatch.upload(UPos.ZERO, Units.PC, data)
    }

    private fun tint(t: GalaxyType): FloatArray = when (t) {
        GalaxyType.ELLIPTICAL, GalaxyType.LENTICULAR -> floatArrayOf(1f, 0.86f, 0.68f)
        GalaxyType.IRREGULAR, GalaxyType.DWARF -> floatArrayOf(0.72f, 0.82f, 1f)
        else -> floatArrayOf(0.88f, 0.9f, 1f)
    }

    /** Indices of galaxies big enough on screen to draw as shapes, largest first. */
    private fun visibleShapes(frame: Frame, limit: Int): List<Pair<Int, Double>> {
        val list = source ?: return emptyList()
        val cam = (frame.camPos - UPos.ZERO) / Units.PC
        val k = frame.height / 2.0 / tan(frame.fovY / 2)
        val out = ArrayList<Pair<Int, Double>>()
        for (i in list.indices) {
            val dx = px[i] - cam.x; val dy = py[i] - cam.y; val dz = pz[i] - cam.z
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            val pix = pr[i] / d * k
            if (pix > 2.5) out.add(i to pix)
        }
        out.sortByDescending { it.second }
        return if (out.size > limit) out.subList(0, limit) else out
    }

    override fun drawTransparent(frame: Frame, rc: RenderContext) {
        val list = source ?: return
        val b = batch ?: return
        val day = 1f - daylight
        if (day < 0.03f) return
        val exp = exposure(frame)
        rc.drawer.beginAdditive()
        rc.drawer.points(frame, b, exp, 2.4f, 0.85f * day, minSize = 1.2f, maxSize = 6f)
        val near = rc.engine.world.nearGalaxies
        val camRight = frame.orientation.rotate(Vec3d.X)
        val camUp = frame.orientation.rotate(Vec3d.Y)
        for ((i, pix) in visibleShapes(frame, 160)) {
            val g = list[i]
            val rel = frame.rel(g.center)
            val dist = rel.length()
            // Close up, the galaxy's own glow and stars (GalaxyLayer) take over.
            val handOff = if (g in near) smoothstep(g.radius * 12, g.radius * 30, dist) else 1.0
            val alpha = (0.75 * smoothstep(2.5, 8.0, pix) * handOff * day).toFloat()
            if (alpha < 0.01f) continue
            val t = g.model.type
            val bulge = floatArrayOf(1f, 0.88f, 0.7f)
            val disk = when (t) {
                GalaxyType.ELLIPTICAL, GalaxyType.LENTICULAR -> floatArrayOf(1f, 0.85f, 0.66f)
                GalaxyType.IRREGULAR, GalaxyType.DWARF -> floatArrayOf(0.62f, 0.75f, 1f)
                else -> floatArrayOf(0.62f, 0.76f, 1f)
            }
            val seed = ((g.seed ushr 20) % 1000) / 10f
            when (t) {
                GalaxyType.SPIRAL, GalaxyType.BARRED -> rc.drawer.billboard(
                    frame, rel, g.frame.rotate(Vec3d.X) * g.radius, g.frame.rotate(Vec3d.Y) * g.radius, 1, bulge, disk,
                    seed, alpha, arms = max(2, g.model.arms).toFloat(), twist = (3.0 / tan(Math.toRadians(g.model.pitchDeg)) / 4).toFloat(),
                )
                GalaxyType.LENTICULAR -> rc.drawer.billboard(
                    frame, rel, g.frame.rotate(Vec3d.X) * g.radius, g.frame.rotate(Vec3d.Y) * g.radius, 2, disk, disk, seed, alpha,
                )
                GalaxyType.ELLIPTICAL -> rc.drawer.billboard(
                    frame, rel, camRight * g.radius * 0.8, camUp * g.radius * 0.6, 2, disk, disk, seed, alpha,
                )
                else -> rc.drawer.billboard(
                    frame, rel, camRight * g.radius * 0.8, camUp * g.radius * 0.8, 5, disk, bulge, seed, alpha * 0.8f,
                )
            }
        }
        rc.drawer.end()
    }

    override fun collect(frame: Frame, rc: RenderContext, out: MutableList<Pickable>) {
        val list = source ?: return
        val exp = exposure(frame)
        val sel = rc.engine.selected
        val cam = (frame.camPos - UPos.ZERO) / Units.PC
        var labels = 0
        for ((i, pix) in visibleShapes(frame, 60)) {
            val g = list[i]
            if (g.isMilkyWay) continue
            val s = frame.project(frame.rel(g.center)) ?: continue
            val named = !g.name.startsWith("PGC ")
            val label = named && labels < 25
            if (label) labels++
            out.add(Pickable(g, s.first, s.second, pix.toFloat().coerceAtMost(160f), 3f, label, 0xFFE8DCC8.toInt()))
        }
        // Bright named galaxies that are still just points.
        if (labels < 12) {
            for ((i, g) in list.withIndex()) {
                if (g.isMilkyWay || g.name.startsWith("PGC ") || g.name.startsWith("NGC ") || g.name.startsWith("IC ")) continue
                val dx = px[i] - cam.x; val dy = py[i] - cam.y; val dz = pz[i] - cam.z
                val d = sqrt(dx * dx + dy * dy + dz * dz)
                val m = g.absMag + 5 * log10(max(d, 1.0)) - 5
                if (m > exp - 2.5) continue
                if (out.any { it.body == g }) continue
                val s = frame.project(frame.rel(g.center)) ?: continue
                out.add(Pickable(g, s.first, s.second, 6f, 2.5f, true, 0xFFE8DCC8.toInt()))
                if (++labels >= 12) break
            }
        }
        if (sel is GalaxyBody && out.none { it.body == sel }) {
            frame.project(frame.rel(sel.center))?.let { out.add(Pickable(sel, it.first, it.second, 8f, 5f, true)) }
        }
    }

    override fun pickAt(frame: Frame, rc: RenderContext, x: Float, y: Float, reach: Float): Body? {
        val list = source ?: return null
        val exp = exposure(frame)
        val cam = (frame.camPos - UPos.ZERO) / Units.PC
        var best = -1
        var bestScore = Double.MAX_VALUE
        for (i in list.indices) {
            val dx = px[i] - cam.x; val dy = py[i] - cam.y; val dz = pz[i] - cam.z
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            val m = list[i].absMag + 5 * log10(max(d, 1.0)) - 5
            if (m > exp + 0.5) continue
            val s = frame.project(Vec3d(dx, dy, dz) * Units.PC) ?: continue
            val dpx = hypot((s.first - x).toDouble(), (s.second - y).toDouble())
            if (dpx > reach) continue
            val score = dpx + (m - exp) * 2.0
            if (score < bestScore) { bestScore = score; best = i }
        }
        return if (best >= 0) list[best] else null
    }
}
