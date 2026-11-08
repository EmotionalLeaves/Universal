package com.emotionalleaves.universe.render

import android.opengl.GLES30
import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import com.emotionalleaves.universe.core.smoothstep
import com.emotionalleaves.universe.engine.Body
import com.emotionalleaves.universe.engine.CosmicWebData
import com.emotionalleaves.universe.engine.GladePack
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max

/**
 * The largest scales: millions of GLADE+ galaxies (if the pack is installed), the generated cosmic
 * web out to the edge, and the Planck map of the cosmic microwave background as the outer shell.
 */
class CosmicLayer : Layer {
    private var web: CosmicWebData? = null
    private var webBatch: PointBatch? = null
    private var glade: GladePack? = null
    private var outer: CosmicWebData? = null
    private var outerBatch: PointBatch? = null
    private var gladeBatch: PointBatch? = null
    var daylight = 0f
    var drawFraction = 1.0

    override fun init(rc: RenderContext) {
        web = null; webBatch = null; glade = null; gladeBatch = null; outer = null; outerBatch = null
    }

    fun exposure(frame: Frame): Float = (7.2 + 5 * log10(max(1.0, frame.focusDistance / (0.3 * Units.MPC)))).toFloat()

    override fun update(frame: Frame, rc: RenderContext) {
        val w = rc.engine.world.cosmicWeb
        if (w !== web) {
            webBatch?.release()
            webBatch = w?.let { PointBatch.upload(UPos.ZERO, Units.MPC, it.data) }
            web = w
        }
        val o = rc.engine.world.outerWeb
        if (o !== outer) {
            outerBatch?.release()
            outerBatch = o?.let { PointBatch.upload(UPos.ZERO, Units.MPC, it.data) }
            outer = o
        }
        val g = rc.engine.world.glade
        if (g !== glade) {
            gladeBatch?.release()
            gladeBatch = g?.let { PointBatch.upload(UPos.ZERO, Units.MPC, it.data) }
            glade = g
        }
    }

    /** How strongly the CMB shell shows: only once you have zoomed out to cosmic scales. */
    private fun cmbVisibility(frame: Frame): Double {
        // Depends only on how far you have zoomed out, never on how far you are from Earth:
        // otherwise the map covers the screen around any distant galaxy you fly to.
        val zoomOut = frame.focusDistance
        return smoothstep(3 * Units.GLY, 40 * Units.GLY, zoomOut)
    }

    override fun drawOpaque(frame: Frame, rc: RenderContext) {
        val vis = cmbVisibility(frame) * (1 - daylight)
        if (vis < 0.01) return
        val tex = rc.textures.get("cmb/planck.jpg")
        val rel = frame.rel(UPos.ZERO)
        val inside = rel.length() < Units.UNIVERSE_RADIUS
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(if (inside) GLES30.GL_FRONT else GLES30.GL_BACK)
        val m = Material(Material.SKY, floatArrayOf(0.95f, 0.6f, 0.25f), floatArrayOf(0.2f, 0.4f, 0.9f), texture = tex, ambient = 1f)
        // From outside (only possible with "beyond" on) it is a see-through bubble, so the inner universe still shows.
        rc.drawer.sphere(frame, rel, Units.UNIVERSE_RADIUS, Quatd.IDENTITY, m, Vec3d.Z, opacity = (vis * if (inside) 0.85 else 0.3).toFloat())
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)
    }

    override fun drawTransparent(frame: Frame, rc: RenderContext) {
        val day = 1f - daylight
        if (day < 0.03f) return
        val exp = exposure(frame)
        // Nothing beyond ~300 Mpc is visible until you zoom out past a few Mpc: skip the work.
        val fromEarth = (frame.camPos - UPos.ZERO).length()
        if (frame.focusDistance < 2 * Units.MPC && fromEarth < 20 * Units.MPC) return
        rc.drawer.beginAdditive()
        gladeBatch?.let { rc.drawer.points(frame, it, exp + 1.5f, 2.2f, 0.85f * day, minSize = 1.3f, maxSize = 5f) }
        webBatch?.let { rc.drawer.points(frame, it, exp + 1.5f, 2.2f, 0.95f * day, minSize = 1.3f, maxSize = 5f) }
        outerBatch?.let { rc.drawer.points(frame, it, exp + 1.5f, 2.2f, 0.9f * day, minSize = 1.3f, maxSize = 5f) }
        rc.drawer.end()
    }

    override fun pickAt(frame: Frame, rc: RenderContext, x: Float, y: Float, reach: Float): Body? =
        pickIn(web, frame, rc, x, y, reach) ?: pickIn(outer, frame, rc, x, y, reach)

    private fun pickIn(w: CosmicWebData?, frame: Frame, rc: RenderContext, x: Float, y: Float, reach: Float): Body? {
        w ?: return null
        val exp = exposure(frame)
        val cam = (frame.camPos - UPos.ZERO) / Units.MPC
        var best = -1
        var bestScore = Double.MAX_VALUE
        val d = w.data
        for (i in 0 until w.count) {
            val dx = d[i * 7] - cam.x; val dy = d[i * 7 + 1] - cam.y; val dz = d[i * 7 + 2] - cam.z
            val dist = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            val m = d[i * 7 + 6] + 5 * log10(max(dist * 1e6, 1.0)) - 5
            if (m > exp + 0.5) continue
            val s = frame.project(Vec3d(dx, dy, dz) * Units.MPC) ?: continue
            val px = hypot((s.first - x).toDouble(), (s.second - y).toDouble())
            if (px > reach) continue
            val score = px + (m - exp) * 2
            if (score < bestScore) { bestScore = score; best = i }
        }
        if (best < 0) return null
        return w.galaxy(best).also { rc.engine.world.registerExtraGalaxy(it) }
    }
}
