package com.emotionalleaves.universe.render

import com.emotionalleaves.universe.core.Rng
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.smoothstep
import com.emotionalleaves.universe.engine.DsoBody
import kotlin.math.max
import kotlin.math.min

/** Nebulae as soft glowing volumes and star clusters as glows that resolve into stars up close. */
class DeepSkyLayer : Layer {
    private val members = HashMap<String, PointBatch>()
    var daylight = 0f

    override fun init(rc: RenderContext) {
        members.clear()
    }

    private fun colors(d: DsoBody): Pair<FloatArray, FloatArray> = when (d.type) {
        "PN" -> floatArrayOf(0.35f, 0.95f, 0.85f) to floatArrayOf(0.9f, 0.4f, 0.5f)
        "RfN" -> floatArrayOf(0.45f, 0.6f, 1.0f) to floatArrayOf(0.7f, 0.8f, 1.0f)
        "SNR" -> floatArrayOf(1.0f, 0.45f, 0.35f) to floatArrayOf(0.4f, 0.8f, 1.0f)
        else -> floatArrayOf(1.0f, 0.32f, 0.45f) to floatArrayOf(0.45f, 0.75f, 1.0f)
    }

    override fun update(frame: Frame, rc: RenderContext) {
        val list = rc.engine.world.dsos
        // Build member stars for at most one nearby cluster per frame; drop far ones.
        var built = false
        for (d in list) {
            if (!d.isCluster && d.type != "Cl+N") continue
            val dist = (d.pos - frame.camPos).length()
            val near = dist < d.radius * 20
            if (near && !members.containsKey(d.id) && !built) {
                val (data, n) = d.memberStars()
                members[d.id] = PointBatch.upload(d.pos, Units.PC, data.copyOf(n * 7))
                built = true
            } else if (!near && members.containsKey(d.id) && dist > d.radius * 30) {
                members.remove(d.id)?.release()
            }
        }
    }

    override fun drawTransparent(frame: Frame, rc: RenderContext) {
        val list = rc.engine.world.dsos
        if (list.isEmpty()) return
        val day = 1f - daylight
        if (day < 0.03f) return
        rc.drawer.beginAdditive()
        val right = frame.orientation.rotate(com.emotionalleaves.universe.core.Vec3d.X)
        val up = frame.orientation.rotate(com.emotionalleaves.universe.core.Vec3d.Y)
        for (d in list) {
            val rel = frame.rel(d.pos)
            val dist = rel.length()
            val px = frame.pixelSize(d.radius, dist)
            if (px < 1.0) continue
            val inside = smoothstep(d.radius * 0.25, d.radius * 1.2, dist)
            val grow = smoothstep(1.0, 10.0, px)
            if (d.hasNebula) {
                val (a, b) = colors(d)
                val style = if (d.type == "PN") 4 else 3
                val alpha = (0.55 * grow * inside * day).toFloat()
                if (alpha > 0.01f) {
                    val seed = (Rng.hashString(d.id) % 1000) / 10f
                    rc.drawer.billboard(frame, rel, right * d.radius, up * d.radius, style, a, b, seed, alpha)
                }
            }
            if (d.isCluster) {
                val c = if (d.type == "GCl") floatArrayOf(1f, 0.85f, 0.6f) else floatArrayOf(0.7f, 0.8f, 1f)
                // The glow gives way to individual stars when you get close.
                val resolve = 1.0 - smoothstep(d.radius * 6, d.radius * 15, dist)
                val alpha = (0.35 * grow * (1 - resolve * 0.85) * day).toFloat()
                if (alpha > 0.01f) {
                    rc.drawer.billboard(frame, rel, right * d.radius * 0.8, up * d.radius * 0.8,
                        if (d.type == "GCl") 2 else 0, c, c, 0f, alpha)
                }
            }
        }
        for ((_, batch) in members) {
            rc.drawer.points(frame, batch, 7.2f + rc.engine.settings.starBrightness, 3.0f, day, minSize = 1.2f, maxSize = 11f)
        }
        rc.drawer.end()
    }

    override fun collect(frame: Frame, rc: RenderContext, out: MutableList<Pickable>) {
        val list = rc.engine.world.dsos
        val sel = rc.engine.selected
        for (d in list) {
            val rel = frame.rel(d.pos)
            val dist = rel.length()
            val px = frame.pixelSize(d.radius, dist).toFloat()
            val famous = d.messier.isNotEmpty() || d.common.isNotEmpty()
            if (px < 2f && d != sel && !(famous && (d.mag ?: 99.0) < 7.5 && dist < 30_000 * Units.LY)) continue
            if (dist < d.radius * 0.3 && d != sel) continue
            val s = frame.project(rel) ?: continue
            val label = famous && px > 1.5f || px > 25f
            out.add(Pickable(d, s.first, s.second, max(min(px, 120f), 8f), if (famous) 3.5f else 2f, label, 0xFFFFB8C8.toInt()))
        }
    }
}
