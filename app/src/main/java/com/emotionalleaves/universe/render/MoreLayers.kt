package com.emotionalleaves.universe.render

import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import com.emotionalleaves.universe.core.smoothstep
import com.emotionalleaves.universe.engine.Body
import com.emotionalleaves.universe.engine.ExoticBody
import com.emotionalleaves.universe.engine.ExoticCatalog
import com.emotionalleaves.universe.engine.Kind
import com.emotionalleaves.universe.engine.RemnantBody
import com.emotionalleaves.universe.engine.SmallBodies
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Real asteroids and comets. Positions are recomputed on a worker thread a few times a second
 * (or as fast as the simulation clock needs) and uploaded as one point batch.
 */
class SmallBodyLayer : Layer {
    private var set: SmallBodies? = null
    private var batch: PointBatch? = null
    @Volatile private var pending: FloatArray? = null
    @Volatile private var busy = false
    private var lastJd = Double.NaN
    private var lastWall = 0L
    /** Last computed positions (AU from the Sun, 3 per body) for picking and spheres. */
    @Volatile private var posAu = FloatArray(0)
    private var radiusM = DoubleArray(0)
    private val worker = Executors.newSingleThreadExecutor()
    var daylight = 0f

    private val pcPerAuMag = (5 * log10(Units.PC / Units.AU) + 5).toFloat()

    override fun init(rc: RenderContext) { batch = null; set = null; lastJd = Double.NaN }

    private fun visibility(frame: Frame): Double {
        val d = (frame.camPos - UPos.ZERO).length()
        return 1.0 - smoothstep(300 * Units.AU, 3000 * Units.AU, d)
    }

    override fun update(frame: Frame, rc: RenderContext) {
        zoomOut = smoothstep(2e9, 6e10, frame.focusDistance).toFloat()
        val sb = rc.engine.world.smallBodies ?: return
        if (sb !== set) {
            set = sb
            radiusM = DoubleArray(sb.count) { sb.body(it).radius }
            lastJd = Double.NaN
        }
        pending?.let { data ->
            batch?.release()
            batch = PointBatch.upload(UPos.ZERO, Units.AU, data)
            pending = null
        }
        if (visibility(frame) <= 0) return
        val now = System.nanoTime()
        val dueByTime = now - lastWall > 250_000_000L
        if (busy || !(lastJd.isNaN() || (dueByTime && kotlin.math.abs(frame.jd - lastJd) > 1e-4))) return
        busy = true
        lastWall = now
        lastJd = frame.jd
        val jd = frame.jd
        worker.execute {
            try {
                val n = sb.count
                val data = FloatArray(n * 7)
                val pos = FloatArray(n * 3)
                for (i in 0 until n) {
                    val p = sb.position(i, jd) / Units.AU
                    val r = p.length()
                    pos[i * 3] = p.x.toFloat(); pos[i * 3 + 1] = p.y.toFloat(); pos[i * 3 + 2] = p.z.toFloat()
                    data[i * 7] = pos[i * 3]; data[i * 7 + 1] = pos[i * 3 + 1]; data[i * 7 + 2] = pos[i * 3 + 2]
                    val c = when (sb.cls[i]) {
                        "COM" -> floatArrayOf(0.7f, 0.9f, 1f)
                        "APO", "ATE", "AMO", "IEO" -> floatArrayOf(1f, 0.7f, 0.45f)
                        "TNO", "CEN" -> floatArrayOf(0.75f, 0.8f, 1f)
                        "TJN" -> floatArrayOf(0.9f, 0.85f, 0.6f)
                        else -> floatArrayOf(0.85f, 0.8f, 0.72f)
                    }
                    data[i * 7 + 3] = c[0]; data[i * 7 + 4] = c[1]; data[i * 7 + 5] = c[2]
                    // Apparent magnitude = H + 5 log10(r * delta) (comets: M1 + 10 log10 r + 5 log10 delta).
                    val k = if (sb.isComet(i)) 10f else 5f
                    data[i * 7 + 6] = (sb.h(i) + k * log10(max(r, 0.05)) + pcPerAuMag).toFloat()
                }
                posAu = pos
                pending = data
            } catch (_: Throwable) {
            } finally {
                busy = false
            }
        }
    }

    /**
     * Brightness cutoff: realistic (like stars, ~7) when you are near a planet looking at the sky,
     * rising to show the whole asteroid belt once you zoom out to solar-system scale.
     */
    private var zoomOut = 0f
    private fun limit(rc: RenderContext) = 7.2f + 6.3f * zoomOut + rc.engine.settings.starBrightness - daylight * 9f

    override fun drawOpaque(frame: Frame, rc: RenderContext) {
        val sb = set ?: return
        val pos = posAu
        if (visibility(frame) <= 0 || pos.size < sb.count * 3) return
        val sun = frame.rel(UPos.ZERO)
        rc.drawer.beginOpaque()
        val cam = (frame.camPos - UPos.ZERO) / Units.AU
        for (i in 0 until sb.count) {
            val dx = pos[i * 3] - cam.x; val dy = pos[i * 3 + 1] - cam.y; val dz = pos[i * 3 + 2] - cam.z
            val distAu = sqrt(dx * dx + dy * dy + dz * dz)
            val r = radiusM[i]
            if (distAu * Units.AU > r * 3000) continue
            // Draw the exact current position for the one you are close to.
            val p = sb.position(i, frame.jd)
            val rel = frame.rel(UPos.ZERO + p)
            val px = frame.pixelSize(r, rel.length())
            if (px < 0.7) continue
            val comet = sb.isComet(i)
            val m = Material(
                if (comet) Material.ICE else Material.ROCKY,
                if (comet) floatArrayOf(0.35f, 0.33f, 0.32f) else floatArrayOf(0.5f, 0.46f, 0.42f),
                floatArrayOf(0.32f, 0.3f, 0.28f), floatArrayOf(0.62f, 0.58f, 0.52f), seed = (i % 997) / 9f, ambient = 0.03f,
            )
            rc.drawer.sphere(frame, rel, r, Quatd.axisAngle(Vec3d.Z, frame.jd * 2.0 % (2 * Math.PI)), m, sun - rel,
                if (px < 40) rc.programs.lowSphereMesh else rc.programs.sphereMesh)
        }
        rc.drawer.end()
    }

    override fun drawTransparent(frame: Frame, rc: RenderContext) {
        val b = batch ?: return
        val vis = visibility(frame)
        if (vis <= 0) return
        rc.drawer.beginAdditive()
        rc.drawer.points(frame, b, limit(rc), 2.2f, (0.9 * vis).toFloat() * (1f - daylight), minSize = 1.1f, maxSize = 5f)
        rc.drawer.end()
    }

    override fun pickAt(frame: Frame, rc: RenderContext, x: Float, y: Float, reach: Float): Body? {
        val sb = set ?: return null
        val pos = posAu
        if (visibility(frame) <= 0 || pos.size < sb.count * 3) return null
        val cam = (frame.camPos - UPos.ZERO) / Units.AU
        val lim = limit(rc)
        var best = -1
        var bestScore = Double.MAX_VALUE
        for (i in 0 until sb.count) {
            val dx = pos[i * 3] - cam.x; val dy = pos[i * 3 + 1] - cam.y; val dz = pos[i * 3 + 2] - cam.z
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            val r = sqrt((pos[i * 3] * pos[i * 3] + pos[i * 3 + 1] * pos[i * 3 + 1] + pos[i * 3 + 2] * pos[i * 3 + 2]).toDouble())
            val k = if (sb.isComet(i)) 10 else 5
            val m = sb.h(i) + k * log10(max(r, 0.05)) + 5 * log10(max(d, 1e-9))
            if (m > lim + 0.5) continue
            val s = frame.project(Vec3d(dx, dy, dz) * Units.AU) ?: continue
            val px = hypot((s.first - x).toDouble(), (s.second - y).toDouble())
            if (px > reach) continue
            val score = px + m
            if (score < bestScore) { bestScore = score; best = i }
        }
        return if (best >= 0) sb.body(best) else null
    }

    override fun collect(frame: Frame, rc: RenderContext, out: MutableList<Pickable>) {
        val sb = set ?: return
        if (visibility(frame) <= 0) return
        val sel = rc.engine.selected
        if (sel is com.emotionalleaves.universe.engine.SmallBody) {
            frame.project(frame.rel(sel.position(frame.jd)))?.let { out.add(Pickable(sel, it.first, it.second, 6f, 6f, true, 0xFFE8D8C0.toInt())) }
        }
        // Label the big ones (Ceres, Vesta, Pallas…) when the asteroid belt fills the view.
        val camFromSun = (frame.camPos - UPos.ZERO).length()
        if (camFromSun > 60 * Units.AU || zoomOut < 0.5f) return
        var n = 0
        for (i in 0 until sb.cometFrom) {
            if (sb.diamKm[i] < 400f) continue
            val b = sb.body(i)
            if (b == sel) continue
            frame.project(frame.rel(b.position(frame.jd)))?.let { out.add(Pickable(b, it.first, it.second, 5f, 3f, true, 0xFFCFC4B4.toInt())) }
            if (++n >= 12) break
        }
    }
}

/** Pulsars, quasars and galaxy clusters from real catalogues. */
class ExoticLayer : Layer {
    private var src: ExoticCatalog? = null
    private var psr: PointBatch? = null
    private var qso: PointBatch? = null
    private var gcl: PointBatch? = null
    var daylight = 0f

    private fun exposure(frame: Frame): Float = (7.2 + 5 * log10(max(1.0, frame.focusDistance / (0.3 * Units.MPC)))).toFloat()

    override fun init(rc: RenderContext) { src = null; psr = null; qso = null; gcl = null }

    private fun upload(list: List<ExoticBody>, unit: Double, mag: (ExoticBody) -> Double): PointBatch? {
        if (list.isEmpty()) return null
        val d = FloatArray(list.size * 7)
        for ((i, b) in list.withIndex()) {
            val p = (b.position(0.0) - UPos.ZERO) / unit
            val c = Material.rgb(b.color)
            d[i * 7] = p.x.toFloat(); d[i * 7 + 1] = p.y.toFloat(); d[i * 7 + 2] = p.z.toFloat()
            d[i * 7 + 3] = c[0]; d[i * 7 + 4] = c[1]; d[i * 7 + 5] = c[2]; d[i * 7 + 6] = mag(b).toFloat()
        }
        return PointBatch.upload(UPos.ZERO, unit, d)
    }

    private fun absMag(b: ExoticBody): Double = when (b.kind) {
        Kind.PULSAR -> -3.5    // marker brightness: pulsars are dim in visible light
        Kind.GALAXY_CLUSTER -> -24.5 - 0.4 * b.richness
        else -> {
            val dc = (b.position(0.0) - UPos.ZERO).length() / Units.PC
            b.mag - 5 * log10(max(dc * (1 + b.z), 1.0)) + 5
        }
    }

    override fun update(frame: Frame, rc: RenderContext) {
        val ex = rc.engine.world.exotic
        if (ex === src) return
        src = ex
        psr?.release(); qso?.release(); gcl?.release()
        psr = upload(ex.pulsars, Units.PC, ::absMag)
        qso = upload(ex.quasars, Units.MPC, ::absMag)
        gcl = upload(ex.clusters, Units.MPC, ::absMag)
    }

    override fun drawTransparent(frame: Frame, rc: RenderContext) {
        val day = 1f - daylight
        if (day < 0.03f) return
        val exp = exposure(frame)
        rc.drawer.beginAdditive()
        // Pulsars are invisible to the eye: show them as markers only when looking at the galaxy
        // from afar (not as fake stars in the night sky), or when one is selected.
        val psrVis = pulsarMarkers(frame)
        psr?.let { if (psrVis > 0.01f) rc.drawer.points(frame, it, 7.2f + rc.engine.settings.starBrightness, 2.2f, 0.8f * day * psrVis, minSize = 1.2f, maxSize = 5f) }
        qso?.let { rc.drawer.points(frame, it, exp, 2.2f, 0.9f * day, minSize = 1.1f, maxSize = 5f) }
        gcl?.let { rc.drawer.points(frame, it, exp, 2.6f, 0.9f * day, minSize = 1.4f, maxSize = 8f) }
        // Close up: a pulsar is a tiny white-hot ball with a glow; a quasar a blinding core.
        val ex = src
        if (ex != null) {
            for (b in ex.pulsars) {
                val rel = frame.rel(b.position(0.0))
                val d = rel.length()
                if (d > 30 * Units.PC) continue
                rc.drawer.glow(frame, rel, b.radius * 8, floatArrayOf(0.7f, 0.85f, 1f), 0.8f * day, minPixels = 3.0)
            }
            val sel = rc.engine.selected
            if (sel is ExoticBody && sel.kind == Kind.QUASAR) {
                val rel = frame.rel(sel.position(0.0))
                if (rel.length() < 2 * Units.MPC) rc.drawer.glow(frame, rel, 2e17, floatArrayOf(0.8f, 0.88f, 1f), 0.9f, minPixels = 10.0)
            }
        }
        rc.drawer.end()
    }

    override fun drawOpaque(frame: Frame, rc: RenderContext) {
        val ex = src ?: return
        rc.drawer.beginOpaque()
        for (b in ex.pulsars) {
            val rel = frame.rel(b.position(0.0))
            val px = frame.pixelSize(b.radius, rel.length())
            if (px < 0.7) continue
            val m = Material(Material.STAR, floatArrayOf(0.8f, 0.88f, 1f), floatArrayOf(0.6f, 0.7f, 1f), floatArrayOf(1f, 1f, 1f), ambient = 1f)
            rc.drawer.sphere(frame, rel, b.radius, Quatd.IDENTITY, m, -rel, if (px < 40) rc.programs.lowSphereMesh else rc.programs.sphereMesh)
        }
        rc.drawer.end()
    }

    private fun pulsarMarkers(frame: Frame): Float {
        val fromSun = (frame.camPos - UPos.ZERO).length()
        val zoom = kotlin.math.max(frame.focusDistance, fromSun)
        return smoothstep(30 * Units.LY, 300 * Units.LY, zoom).toFloat()
    }

    override fun pickAt(frame: Frame, rc: RenderContext, x: Float, y: Float, reach: Float): Body? {
        val ex = src ?: return null
        val exp = exposure(frame)
        var best: ExoticBody? = null
        var bestScore = Double.MAX_VALUE
        for (list in listOf(ex.clusters, ex.quasars, ex.pulsars)) for (b in list) {
            val rel = frame.rel(b.position(0.0))
            val dist = rel.length()
            if (b.kind == Kind.PULSAR && pulsarMarkers(frame) < 0.3f && (b.position(0.0) - frame.camPos).length() > 30 * Units.PC) continue
            val lim = if (b.kind == Kind.PULSAR) 7.2 + rc.engine.settings.starBrightness else exp.toDouble()
            val m = absMag(b) + 5 * log10(max(dist / Units.PC, 1.0)) - 5
            if (m > lim + 0.5) continue
            val s = frame.project(rel) ?: continue
            val px = hypot((s.first - x).toDouble(), (s.second - y).toDouble())
            if (px > reach) continue
            val score = px + (m - lim) * 2
            if (score < bestScore) { bestScore = score; best = b }
        }
        return best
    }

    override fun collect(frame: Frame, rc: RenderContext, out: MutableList<Pickable>) {
        val ex = src ?: return
        val sel = rc.engine.selected
        if (sel is ExoticBody) frame.project(frame.rel(sel.position(0.0)))?.let { out.add(Pickable(sel, it.first, it.second, 6f, 6f, true)) }
        // Name the richest clusters when you are zoomed out far enough to see them.
        if (frame.focusDistance < 30 * Units.MPC) return
        val exp = exposure(frame)
        var n = 0
        for (b in ex.clusters) {
            if (b.richness < 2 || b == sel) continue
            val rel = frame.rel(b.position(0.0))
            val m = absMag(b) + 5 * log10(max(rel.length() / Units.PC, 1.0)) - 5
            if (m > exp - 3) continue
            frame.project(rel)?.let { out.add(Pickable(b, it.first, it.second, 6f, 2f, true, 0xFFFFD8A8.toInt())) }
            if (++n >= 10) break
        }
    }
}

/** Generated black holes, neutron stars and rogue planets near the camera. */
class RemnantLayer : Layer {
    var daylight = 0f
    /** The ones close enough to show, nearest first (at most [MAX] per frame). */
    private var near: List<Pair<RemnantBody, Double>> = emptyList()

    private fun reach(b: RemnantBody) = when (b.kind) { Kind.BLACK_HOLE -> 25 * Units.PC; Kind.NEUTRON_STAR -> 40 * Units.PC; else -> 0.6 * Units.PC }

    override fun update(frame: Frame, rc: RenderContext) {
        val bodies = rc.engine.world.remnants?.bodies ?: emptyList()
        val sel = rc.engine.selected
        val out = ArrayList<Pair<RemnantBody, Double>>()
        // Markers are a travel aid between the stars; inside a star system only nearby ones show.
        val interstellar = frame.focusDistance > 0.02 * Units.LY
        for (b in bodies) {
            val d = (b.pos - frame.camPos).length()
            val r = if (interstellar) reach(b) else minOf(reach(b), 0.05 * Units.LY)
            if (d < r || b == sel) out.add(b to d)
        }
        out.sortBy { it.second }
        near = if (out.size > MAX) out.subList(0, MAX) else out
    }

    override fun drawOpaque(frame: Frame, rc: RenderContext) {
        if (near.isEmpty()) return
        rc.drawer.beginOpaque()
        for ((b, _) in near) {
            val rel = frame.rel(b.pos)
            val px = frame.pixelSize(b.radius, rel.length())
            if (px < 0.7) continue
            val m = when (b.kind) {
                Kind.BLACK_HOLE -> Material(Material.PLAIN, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f), ambient = 0f)
                Kind.NEUTRON_STAR -> Material(Material.STAR, floatArrayOf(0.8f, 0.88f, 1f), floatArrayOf(0.6f, 0.7f, 1f), floatArrayOf(1f, 1f, 1f), ambient = 1f)
                else -> Material(Material.ICE, floatArrayOf(0.22f, 0.2f, 0.22f), floatArrayOf(0.12f, 0.11f, 0.13f), floatArrayOf(0.3f, 0.28f, 0.3f), seed = (b.id.hashCode() % 997) / 9f, ambient = 0.12f)
            }
            rc.drawer.sphere(frame, rel, b.radius, Quatd.IDENTITY, m, -rel, if (px < 40) rc.programs.lowSphereMesh else rc.programs.sphereMesh)
        }
        rc.drawer.end()
    }

    override fun drawTransparent(frame: Frame, rc: RenderContext) {
        if (near.isEmpty() || daylight > 0.9f) return
        rc.drawer.beginAdditive()
        for ((b, d) in near) {
            val rel = frame.rel(b.pos)
            when (b.kind) {
                Kind.BLACK_HOLE -> {
                    // Light bent around the hole: a faint ring a few horizon sizes across, and a marker from afar.
                    if (frame.pixelSize(b.radius, d) > 0.5) rc.drawer.glow(frame, rel, b.radius * 2.6, floatArrayOf(0.75f, 0.6f, 1f), 0.35f)
                    else rc.drawer.glow(frame, rel, 0.0, floatArrayOf(0.62f, 0.45f, 1f), 0.45f, minPixels = 4.0)
                }
                Kind.NEUTRON_STAR -> rc.drawer.glow(frame, rel, b.radius * 6, floatArrayOf(0.7f, 0.85f, 1f), 0.55f, minPixels = 3.0)
                else -> rc.drawer.glow(frame, rel, 0.0, floatArrayOf(0.7f, 0.42f, 0.38f), 0.35f, minPixels = 2.5)
            }
        }
        rc.drawer.end()
    }

    override fun collect(frame: Frame, rc: RenderContext, out: MutableList<Pickable>) {
        val sel = rc.engine.selected
        for ((i, pair) in near.withIndex()) {
            val (b, d) = pair
            val s = frame.project(frame.rel(b.pos)) ?: continue
            val label = b == sel || i < 6
            val px = frame.pixelSize(b.radius, d).toFloat()
            out.add(Pickable(b, s.first, s.second, max(px, 6f), if (b == sel) 6f else 2f, label,
                when (b.kind) { Kind.BLACK_HOLE -> 0xFFC8A8FF.toInt(); Kind.NEUTRON_STAR -> 0xFFBFD8FF.toInt(); else -> 0xFFD8B8B0.toInt() }))
        }
    }

    companion object {
        const val MAX = 80
    }
}
