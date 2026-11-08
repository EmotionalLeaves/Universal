package com.emotionalleaves.universe.render

import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import com.emotionalleaves.universe.engine.Body
import com.emotionalleaves.universe.engine.Kind
import com.emotionalleaves.universe.engine.NetBody
import com.emotionalleaves.universe.engine.NetSmallBody
import com.emotionalleaves.universe.engine.Online

/** Objects found by online search (SIMBAD, NED, JPL): drawn where they really are, with a label. */
class OnlineLayer : Layer {
    private var visible: List<Pair<Body, Double>> = emptyList()

    /** How far away an object's dot and label still show. */
    private fun range(b: Body) = when (b) {
        is NetSmallBody -> 20 * Units.AU
        else -> when (b.kind) {
            Kind.STAR, Kind.PULSAR, Kind.NEUTRON_STAR -> 300 * Units.PC
            Kind.NEBULA, Kind.CLUSTER, Kind.BLACK_HOLE -> 20_000 * Units.PC
            else -> 3e9 * Units.PC
        }
    }

    override fun update(frame: Frame, rc: RenderContext) {
        val sel = rc.engine.selected
        val list = Online.resolved
        visible = if (list.isEmpty()) emptyList() else list.mapNotNull { b ->
            val d = (b.position(frame.jd) - frame.camPos).length()
            if (d < range(b) || b == sel) b to d else null
        }
    }

    override fun drawOpaque(frame: Frame, rc: RenderContext) {
        if (visible.isEmpty()) return
        var began = false
        for ((b, d) in visible) {
            if (b.radius <= 0) continue
            val px = frame.pixelSize(b.radius, d)
            if (px < 0.7) continue
            val rel = frame.rel(b.position(frame.jd))
            val c = Material.rgb(b.color)
            val m = when (b.kind) {
                Kind.STAR -> Material(Material.STAR, floatArrayOf(c[0] * 0.85f, c[1] * 0.8f, c[2] * 0.7f), floatArrayOf(c[0] * 0.5f, c[1] * 0.4f, c[2] * 0.3f), floatArrayOf(0.9f, 0.85f, 0.75f), ambient = 1f)
                Kind.ASTEROID, Kind.COMET -> Material(if (b.kind == Kind.COMET) Material.ICE else Material.ROCKY, floatArrayOf(0.5f, 0.46f, 0.42f),
                    floatArrayOf(0.32f, 0.3f, 0.28f), floatArrayOf(0.62f, 0.58f, 0.52f), seed = (b.id.hashCode() and 255) / 9f, ambient = 0.03f)
                else -> continue
            }
            if (!began) { rc.drawer.beginOpaque(); began = true }
            val light = if (b.kind == Kind.STAR) -rel else frame.rel(com.emotionalleaves.universe.core.UPos.ZERO) - rel
            rc.drawer.sphere(frame, rel, b.radius, Quatd.axisAngle(Vec3d.Z, frame.jd % (2 * Math.PI)), m, light,
                if (px < 40) rc.programs.lowSphereMesh else rc.programs.sphereMesh)
        }
        if (began) rc.drawer.end()
    }

    override fun drawTransparent(frame: Frame, rc: RenderContext) {
        if (visible.isEmpty()) return
        rc.drawer.beginAdditive()
        for ((b, _) in visible) {
            val rel = frame.rel(b.position(frame.jd))
            val c = Material.rgb(b.color)
            val size = when {
                b.kind == Kind.STAR -> b.radius * 2.0
                b.radius > 0 && b is NetBody -> b.radius * 1.5
                else -> 0.0
            }
            rc.drawer.glow(frame, rel, size, c, if (b is NetSmallBody) 0.5f else 0.6f, minPixels = 3.0)
        }
        rc.drawer.end()
    }

    override fun collect(frame: Frame, rc: RenderContext, out: MutableList<Pickable>) {
        val sel = rc.engine.selected
        for ((b, d) in visible) {
            val s = frame.project(frame.rel(b.position(frame.jd))) ?: continue
            val px = frame.pixelSize(b.radius, d).toFloat()
            out.add(Pickable(b, s.first, s.second, kotlin.math.max(px, 7f), if (b == sel) 6f else 3f, true, 0xFF000000.toInt() or b.color))
        }
    }
}
