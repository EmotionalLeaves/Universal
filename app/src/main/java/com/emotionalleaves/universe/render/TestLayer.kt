package com.emotionalleaves.universe.render

import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.core.Rng
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d

/** Round 2 scale test: Earth-sized sphere, a 1 m cube on it, a marker 10 billion light years away. */
class TestLayer : Layer {
    var enabled = true
    private val planetMat = Material(
        Material.OCEAN,
        floatArrayOf(0.08f, 0.22f, 0.5f), floatArrayOf(0.25f, 0.45f, 0.18f), floatArrayOf(0.6f, 0.55f, 0.45f),
        seed = 3.7f, ambient = 0.08f,
    )
    private val cubeMat = Material(Material.PLAIN, floatArrayOf(1f, 0.55f, 0.15f), ambient = 0.35f)
    private val light = Vec3d(1.0, 0.6, 0.4).normalized()

    override fun drawOpaque(frame: Frame, rc: RenderContext) {
        if (!enabled) return
        val w = rc.engine.world
        rc.drawer.beginOpaque()
        rc.drawer.sphere(frame, frame.rel(w.testPlanet.position(frame.jd)), w.testPlanet.radius, Quatd.IDENTITY, planetMat, light)
        val up = w.cubeOrientationUp
        val rot = Quatd.lookRotation(Vec3d.perpendicular(up), up)
        val cubeRel = frame.rel(w.testCube.position(frame.jd))
        rc.drawer.sphere(frame, cubeRel, 0.5, rot, cubeMat, light, rc.programs.cube)
        rc.drawer.end()
    }

    override fun drawTransparent(frame: Frame, rc: RenderContext) {
        if (!enabled) return
        val w = rc.engine.world
        rc.drawer.beginAdditive()
        val rel = frame.rel(w.farMarker.position(frame.jd))
        rc.drawer.glow(frame, rel, 1e21, floatArrayOf(1f, 0.8f, 0.3f), 1f, minPixels = 14.0)
        rc.drawer.atmosphere(frame, frame.rel(w.testPlanet.position(frame.jd)), w.testPlanet.radius * 1.025,
            floatArrayOf(0.35f, 0.6f, 1f), light, 1.2f)
        rc.drawer.end()
    }

    override fun collect(frame: Frame, rc: RenderContext, out: MutableList<Pickable>) {
        if (!enabled) return
        for (b in rc.engine.world.testBodies()) {
            val rel = frame.rel(b.position(frame.jd))
            val s = frame.project(rel) ?: continue
            val px = frame.pixelSize(b.radius, rel.length()).toFloat()
            out.add(Pickable(b, s.first, s.second, px, if (b.radius > 1) 2f else 1f, showLabel = true))
        }
    }

    companion object {
        /** A sprinkle of distant stars so you can see the view turning (replaced by real stars in Round 5). */
        fun backgroundStars(): PointBatch {
            val r = Rng(42)
            val n = 6000
            val data = FloatArray(n * 7)
            for (i in 0 until n) {
                val v = r.unitVector() * 1000.0
                val mag = (-1.0 + 7.0 * Math.pow(r.nextDouble(), 0.4)).toFloat()
                data[i * 7] = v.x.toFloat(); data[i * 7 + 1] = v.y.toFloat(); data[i * 7 + 2] = v.z.toFloat()
                val warm = r.nextDouble().toFloat()
                data[i * 7 + 3] = 0.8f + 0.2f * warm; data[i * 7 + 4] = 0.85f; data[i * 7 + 5] = 1f - 0.25f * warm
                data[i * 7 + 6] = mag - 10f
            }
            return PointBatch.upload(UPos.ZERO, Units.PC, data)
        }
    }
}
