package com.emotionalleaves.universe.render

import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.engine.Famous
import com.emotionalleaves.universe.engine.FamousBody
import com.emotionalleaves.universe.engine.Kind

/** Record holders and famous objects (see [Famous]): black holes, giant stars, early galaxies, voids, Voyagers. */
class FamousLayer : Layer {
    private var visible: List<Pair<FamousBody, Double>> = emptyList()

    override fun update(frame: Frame, rc: RenderContext) {
        val sel = rc.engine.selected
        visible = Famous.all.mapNotNull { b ->
            val d = (b.position(frame.jd) - frame.camPos).length()
            if (d < b.labelRange || b == sel) b to d else null
        }
    }

    override fun drawOpaque(frame: Frame, rc: RenderContext) {
        if (visible.isEmpty()) return
        rc.drawer.beginOpaque()
        for ((b, d) in visible) {
            val rel = frame.rel(b.position(frame.jd))
            val px = frame.pixelSize(b.radius, d)
            if (px < 0.7) continue
            val c = Material.rgb(b.color)
            val m = when (b.kind) {
                Kind.BLACK_HOLE, Kind.QUASAR -> Material(Material.PLAIN, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f), ambient = 0f)
                // Cool supergiants are deep orange-red, not white: keep the surface below full brightness.
                Kind.STAR -> Material(Material.STAR, floatArrayOf(c[0] * 0.8f, c[1] * 0.62f, c[2] * 0.5f), floatArrayOf(c[0] * 0.5f, c[1] * 0.3f, c[2] * 0.2f), floatArrayOf(0.85f, 0.7f, 0.55f), ambient = 1f)
                Kind.SPACECRAFT -> Material(Material.PLAIN, floatArrayOf(0.75f, 0.75f, 0.72f), floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(0.8f, 0.8f, 0.8f), ambient = 0.35f)
                else -> continue
            }
            rc.drawer.sphere(frame, rel, b.radius, Quatd.IDENTITY, m, -rel, if (px < 40) rc.programs.lowSphereMesh else rc.programs.sphereMesh)
        }
        rc.drawer.end()
    }

    override fun drawTransparent(frame: Frame, rc: RenderContext) {
        if (visible.isEmpty()) return
        rc.drawer.beginAdditive()
        for ((b, d) in visible) {
            val rel = frame.rel(b.position(frame.jd))
            val c = Material.rgb(b.color)
            when (b.kind) {
                Kind.QUASAR -> {
                    // A blinding accretion disk far bigger than the hole, and a ring of bent light round it.
                    rc.drawer.glow(frame, rel, b.radius * 40, floatArrayOf(1f, 0.62f, 0.3f), 0.22f, minPixels = 5.0)
                    rc.drawer.glow(frame, rel, b.radius * 5, floatArrayOf(1f, 0.85f, 0.6f), 0.3f)
                    rc.drawer.glow(frame, rel, b.radius * 2.6, floatArrayOf(0.85f, 0.75f, 1f), 0.3f)
                }
                Kind.BLACK_HOLE -> {
                    rc.drawer.glow(frame, rel, b.radius * 2.6, floatArrayOf(0.8f, 0.65f, 1f), 0.4f)
                    rc.drawer.glow(frame, rel, b.radius * 9, floatArrayOf(1f, 0.6f, 0.3f), 0.18f, minPixels = 4.0)
                }
                Kind.STAR -> rc.drawer.glow(frame, rel, b.radius * 2.0, floatArrayOf(c[0], c[1] * 0.8f, c[2] * 0.65f), 0.3f, minPixels = 3.0)
                Kind.GALAXY -> rc.drawer.glow(frame, rel, b.radius * 3, floatArrayOf(0.85f, 0.85f, 1f), 0.6f, minPixels = 3.0)
                Kind.GALAXY_CLUSTER -> if (d > 200 * com.emotionalleaves.universe.core.Units.MPC) rc.drawer.glow(frame, rel, b.radius * 1.2, floatArrayOf(1f, 0.85f, 0.65f), (0.25 * kotlin.math.min(1.0, d / (b.radius * 3))).toFloat(), minPixels = 3.0)
                Kind.SPACECRAFT -> rc.drawer.glow(frame, rel, 0.0, floatArrayOf(0.9f, 0.95f, 1f), 0.7f, minPixels = 4.0)
                else -> {}
            }
        }
        rc.drawer.end()
    }

    override fun collect(frame: Frame, rc: RenderContext, out: MutableList<Pickable>) {
        val sel = rc.engine.selected
        for ((b, d) in visible) {
            val s = frame.project(frame.rel(b.position(frame.jd))) ?: continue
            val px = frame.pixelSize(b.radius, d).toFloat()
            // Voids and superclusters are named only when you are out at their scale.
            val big = b.kind == Kind.VOID || b.kind == Kind.SUPERCLUSTER || (b.kind == Kind.GALAXY_CLUSTER && b.radius > 1e22)
            // Others are named once you are zoomed out to a scale where they matter (or are close).
            val label = b == sel || if (big) frame.focusDistance > b.radius * 0.5 && px < 600f else px > 3f || frame.focusDistance > d * 0.02
            // A region's tap target is its name, not its whole extent (it swallowed taps on everything inside).
            out.add(Pickable(b, s.first, s.second, if (big) 10f else kotlin.math.max(px, 7f), if (b == sel) 6f else if (big) 1.5f else 3.5f, label, 0xFF000000.toInt() or b.color))
        }
    }
}
