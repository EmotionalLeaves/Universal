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
    /** The full asteroid list is drawn by [AsteroidCloudLayer]: draw only comets here. */
    var skipAsteroids = false

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
        for (i in (if (skipAsteroids) sb.cometFrom else 0) until sb.count) {
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
        b.first = if (skipAsteroids) set?.cometFrom ?: 0 else 0
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
        for (i in (if (skipAsteroids) sb.cometFrom else 0) until sb.count) {
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

    private fun exposure(frame: Frame): Float = Exposure.of(frame)

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

    private var mq: com.emotionalleaves.universe.engine.MilliquasDb? = null

    override fun update(frame: Frame, rc: RenderContext) {
        val m = rc.engine.world.milliquas
        if (m !== mq) {
            mq = m
            qso?.release()
            qso = if (m != null) {
                val d = FloatArray(m.count * 7)
                for (i in 0 until m.count) {
                    d[i * 7] = m.xyz[i * 3]; d[i * 7 + 1] = m.xyz[i * 3 + 1]; d[i * 7 + 2] = m.xyz[i * 3 + 2]
                    d[i * 7 + 3] = 0.78f; d[i * 7 + 4] = 0.85f; d[i * 7 + 5] = 1f; d[i * 7 + 6] = m.absMag(i).toFloat()
                }
                PointBatch.upload(UPos.ZERO, Units.MPC, d)
            } else src?.let { upload(it.quasars, Units.MPC, ::absMag) }
        }
        val ex = rc.engine.world.exotic
        if (ex === src) return
        src = ex
        psr?.release(); qso?.release(); gcl?.release()
        psr = upload(ex.pulsars, Units.PC, ::absMag)
        if (mq == null) qso = upload(ex.quasars, Units.MPC, ::absMag)
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
        qso?.let { rc.drawer.points(frame, it, exp - 2.4f, 2.2f, 0.9f * day, minSize = 1.1f, maxSize = 5f) }
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
        // With Milliquas installed, its million quasars replace the built-in 56,000.
        val m = mq
        if (m != null) {
            val camMpc = (frame.camPos - UPos.ZERO) / Units.MPC
            var bi = -1
            for (i in 0 until m.count) {
                val rel = Vec3d(m.xyz[i * 3] - camMpc.x, m.xyz[i * 3 + 1] - camMpc.y, m.xyz[i * 3 + 2] - camMpc.z)
                val app = m.absMag(i) + 5 * log10(max(rel.length() * 1e6, 1.0)) - 5
                if (app > exp + 0.5) continue
                val s = frame.project(rel * Units.MPC) ?: continue
                val px = hypot((s.first - x).toDouble(), (s.second - y).toDouble())
                if (px > reach) continue
                val score = px + (app - exp) * 2
                if (score < bestScore) { bestScore = score; bi = i }
            }
            if (bi >= 0) best = m.body(bi)
        }
        for (list in listOf(ex.clusters, if (m == null) ex.quasars else emptyList(), ex.pulsars)) for (b in list) {
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

    private fun reach(b: RemnantBody) = when (b.kind) {
        Kind.BLACK_HOLE -> 25 * Units.PC
        Kind.NEUTRON_STAR -> 40 * Units.PC
        Kind.CLUSTER, Kind.NEBULA -> 900 * Units.PC
        else -> 0.6 * Units.PC
    }

    override fun update(frame: Frame, rc: RenderContext) {
        val bodies = rc.engine.world.remnants?.bodies ?: emptyList()
        val sel = rc.engine.selected
        val out = ArrayList<Pair<RemnantBody, Double>>()
        // Black hole and neutron star markers are a travel aid between the stars; inside a star system
        // only nearby ones show. Clusters and nebulae are visible light, so they always show.
        val interstellar = frame.focusDistance > 0.02 * Units.LY
        for (b in bodies) {
            val d = (b.pos - frame.camPos).length()
            val visible = b.kind == Kind.CLUSTER || b.kind == Kind.NEBULA
            val r = if (interstellar || visible) reach(b) else minOf(reach(b), 0.05 * Units.LY)
            if (d < r || b == sel) out.add(b to d)
        }
        out.sortBy { it.second }
        // Clusters and nebulae always make the list; the dark ones fill what is left, nearest first.
        val bright = out.filter { it.first.kind == Kind.CLUSTER || it.first.kind == Kind.NEBULA }
        val dark = out.filter { it.first.kind != Kind.CLUSTER && it.first.kind != Kind.NEBULA }
        near = bright + dark.take(MAX)
    }

    override fun drawOpaque(frame: Frame, rc: RenderContext) {
        if (near.isEmpty()) return
        rc.drawer.beginOpaque()
        for ((b, _) in near) {
            if (b.kind == Kind.CLUSTER || b.kind == Kind.NEBULA) continue
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
        // Towards the crowded galactic centre hundreds of nebula glows piled into a pink fog: nearest 40 only.
        var nebulaeDrawn = 0
        for ((b, d) in near) {
            val rel = frame.rel(b.pos)
            when (b.kind) {
                Kind.BLACK_HOLE -> {
                    // Light bent around the hole: a faint ring a few horizon sizes across, and a marker from afar.
                    if (frame.pixelSize(b.radius, d) > 0.5) rc.drawer.glow(frame, rel, b.radius * 2.6, floatArrayOf(0.75f, 0.6f, 1f), 0.35f)
                    else rc.drawer.glow(frame, rel, 0.0, floatArrayOf(0.62f, 0.45f, 1f), 0.45f, minPixels = 4.0)
                }
                Kind.NEUTRON_STAR -> rc.drawer.glow(frame, rel, b.radius * 6, floatArrayOf(0.7f, 0.85f, 1f), 0.55f, minPixels = 3.0)
                // Clusters sparkle blue-white, nebulae glow pink-red; both fade as you fly inside.
                Kind.CLUSTER -> rc.drawer.glow(frame, rel, b.radius * 1.4, floatArrayOf(0.66f, 0.78f, 1f), (0.5 * kotlin.math.min(1.0, d / (b.radius * 2))).toFloat(), minPixels = 3.0)
                Kind.NEBULA -> if (nebulaeDrawn++ < 40) rc.drawer.glow(frame, rel, b.radius * 1.6, floatArrayOf(1f, 0.45f, 0.62f), (0.3 * kotlin.math.min(1.0, d / (b.radius * 2))).toFloat(), minPixels = 4.0)
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
                when (b.kind) {
                    Kind.BLACK_HOLE -> 0xFFC8A8FF.toInt(); Kind.NEUTRON_STAR -> 0xFFBFD8FF.toInt()
                    Kind.CLUSTER -> 0xFFB8CCFF.toInt(); Kind.NEBULA -> 0xFFFFA8C0.toInt(); else -> 0xFFD8B8B0.toInt()
                }))
        }
    }

    companion object {
        const val MAX = 140
    }
}

/** Asteroid belts and icy outer belts of the star system you are in (generated, tappable). */
class BeltLayer : Layer {
    private var forStar: String? = null
    private var belts: List<com.emotionalleaves.universe.engine.Belt> = emptyList()
    private val batches = ArrayList<PointBatch>()
    var daylight = 0f
    private var zoomOut = 0f

    override fun init(rc: RenderContext) { forStar = null; belts = emptyList(); batches.clear() }

    override fun update(frame: Frame, rc: RenderContext) {
        zoomOut = smoothstep(2e9, 6e10, frame.focusDistance).toFloat()
        val sys = rc.engine.world.activeSystem
        val id = sys?.star?.id
        if (id == forStar) return
        forStar = id
        for (b in batches) b.release()
        batches.clear()
        belts = if (sys != null && rc.engine.settings.showGenerated) com.emotionalleaves.universe.engine.Belts.forSystem(sys) else emptyList()
        for (b in belts) batches.add(PointBatch.upload(b.star.pos, Units.AU, b.data))
    }

    private fun limit(rc: RenderContext) = 7.2f + 6.3f * zoomOut + rc.engine.settings.starBrightness - daylight * 9f

    override fun drawTransparent(frame: Frame, rc: RenderContext) {
        if (batches.isEmpty()) return
        rc.drawer.beginAdditive()
        for (b in batches) rc.drawer.points(frame, b, limit(rc), 2.0f, 0.85f * (1f - daylight), minSize = 1.1f, maxSize = 4f)
        rc.drawer.end()
    }

    override fun drawOpaque(frame: Frame, rc: RenderContext) {
        val sel = rc.engine.selected as? com.emotionalleaves.universe.engine.BeltBody ?: return
        val rel = frame.rel(sel.position(frame.jd))
        val px = frame.pixelSize(sel.radius, rel.length())
        if (px < 0.7) return
        val star = frame.rel(sel.belt.star.pos)
        val m = Material(if (sel.belt.icy) Material.ICE else Material.ROCKY, floatArrayOf(0.5f, 0.46f, 0.42f), floatArrayOf(0.32f, 0.3f, 0.28f),
            floatArrayOf(0.62f, 0.58f, 0.52f), seed = (sel.index % 997) / 9f, ambient = 0.03f)
        rc.drawer.beginOpaque()
        rc.drawer.sphere(frame, rel, sel.radius, Quatd.IDENTITY, m, star - rel, if (px < 40) rc.programs.lowSphereMesh else rc.programs.sphereMesh)
        rc.drawer.end()
    }

    override fun pickAt(frame: Frame, rc: RenderContext, x: Float, y: Float, reach: Float): Body? {
        val lim = limit(rc)
        var best: Pair<com.emotionalleaves.universe.engine.Belt, Int>? = null
        var bestScore = Double.MAX_VALUE
        for (b in belts) {
            val d = b.data
            for (i in 0 until b.count) {
                val p = b.star.pos + Vec3d(d[i * 7].toDouble(), d[i * 7 + 1].toDouble(), d[i * 7 + 2].toDouble()) * Units.AU
                val rel = frame.rel(p)
                val dist = rel.length()
                val m = d[i * 7 + 6] + 5 * log10(max(dist / Units.PC, 1e-12)) - 5
                if (m > lim + 0.5) continue
                val s = frame.project(rel) ?: continue
                val px = hypot((s.first - x).toDouble(), (s.second - y).toDouble())
                if (px > reach) continue
                val score = px + m
                if (score < bestScore) { bestScore = score; best = b to i }
            }
        }
        return best?.let { it.first.body(it.second) }
    }

    override fun collect(frame: Frame, rc: RenderContext, out: MutableList<Pickable>) {
        val sel = rc.engine.selected as? com.emotionalleaves.universe.engine.BeltBody ?: return
        frame.project(frame.rel(sel.position(frame.jd)))?.let { out.add(Pickable(sel, it.first, it.second, 6f, 6f, true, 0xFFE8D8C0.toInt())) }
    }
}

/** Generated rocks, boulders and comet nuclei near the camera (belts and clouds, down to 1 m). */
class RockLayer : Layer {
    private var src: com.emotionalleaves.universe.engine.RockField.Generated? = null
    private var batch: PointBatch? = null
    var daylight = 0f
    private val pcAu = (5 * log10(Units.PC / Units.AU) + 5).toFloat()

    override fun init(rc: RenderContext) { src = null; batch = null }

    override fun update(frame: Frame, rc: RenderContext) {
        val g = rc.engine.world.rocks
        if (g === src) return
        src = g
        batch?.release(); batch = null
        if (g == null || g.bodies.isEmpty()) return
        val d = FloatArray(g.bodies.size * 7)
        for ((i, b) in g.bodies.withIndex()) {
            val rel = (b.pos - g.anchor) / Units.AU
            d[i * 7] = rel.x.toFloat(); d[i * 7 + 1] = rel.y.toFloat(); d[i * 7 + 2] = rel.z.toFloat()
            if (b.zone.icy) { d[i * 7 + 3] = 0.78f; d[i * 7 + 4] = 0.84f; d[i * 7 + 5] = 1f } else { d[i * 7 + 3] = 0.86f; d[i * 7 + 4] = 0.78f; d[i * 7 + 5] = 0.66f }
            val h = 5 * log10(1329.0 / (b.km * 0.316))
            d[i * 7 + 6] = (h + 5 * log10(max(b.distanceAu, 0.05))).toFloat() + pcAu
        }
        batch = PointBatch.upload(g.anchor, Units.AU, d)
    }

    override fun drawOpaque(frame: Frame, rc: RenderContext) {
        val g = src ?: return
        var drawn = 0
        rc.drawer.beginOpaque()
        for (b in g.bodies) {
            val rel = frame.rel(b.pos)
            val px = frame.pixelSize(b.radius, rel.length())
            if (px < 0.7) continue
            val star = frame.rel(b.zone.center)
            val m = Material(if (b.zone.icy) Material.ICE else Material.ROCKY,
                if (b.zone.icy) floatArrayOf(0.62f, 0.64f, 0.7f) else floatArrayOf(0.48f, 0.44f, 0.4f),
                floatArrayOf(0.3f, 0.28f, 0.27f), floatArrayOf(0.6f, 0.57f, 0.52f), seed = (b.id.hashCode() % 997) / 9f, ambient = 0.04f)
            rc.drawer.sphere(frame, rel, b.radius, Quatd.IDENTITY, m, star - rel, if (px < 40) rc.programs.lowSphereMesh else rc.programs.sphereMesh)
            if (++drawn > 60) break
        }
        rc.drawer.end()
    }

    override fun drawTransparent(frame: Frame, rc: RenderContext) {
        val b = batch ?: return
        rc.drawer.beginAdditive()
        rc.drawer.points(frame, b, 7.2f + rc.engine.settings.starBrightness - daylight * 9f, 2.0f, 0.85f * (1f - daylight), minSize = 1.1f, maxSize = 4f)
        rc.drawer.end()
    }

    override fun pickAt(frame: Frame, rc: RenderContext, x: Float, y: Float, reach: Float): Body? {
        val g = src ?: return null
        var best: Body? = null
        var bestScore = Double.MAX_VALUE
        val lim = 7.7 + rc.engine.settings.starBrightness
        for (b in g.bodies) {
            val rel = frame.rel(b.pos)
            val dAu = rel.length() / Units.AU
            val m = 5 * log10(1329.0 / (b.km * 0.316)) + 5 * log10(max(b.distanceAu, 0.05)) + 5 * log10(max(dAu, 1e-12))
            val px = frame.pixelSize(b.radius, rel.length())
            if (m > lim && px < 2) continue
            val s = frame.project(rel) ?: continue
            val dpx = hypot((s.first - x).toDouble(), (s.second - y).toDouble()) - px
            if (dpx > reach) continue
            val score = dpx + m
            if (score < bestScore) { bestScore = score; best = b }
        }
        return best
    }

    override fun collect(frame: Frame, rc: RenderContext, out: MutableList<Pickable>) {
        val g = src ?: return
        val sel = rc.engine.selected
        if (sel is com.emotionalleaves.universe.engine.RockBody) {
            frame.project(frame.rel(sel.pos))?.let { out.add(Pickable(sel, it.first, it.second, 6f, 6f, true, 0xFFE8D8C0.toInt())) }
        }
        // Name the few rocks big enough on screen to see as more than a dot.
        var n = 0
        for (b in g.bodies) {
            if (b == sel) continue
            val rel = frame.rel(b.pos)
            val px = frame.pixelSize(b.radius, rel.length()).toFloat()
            if (px < 3f) continue
            val s = frame.project(rel) ?: continue
            out.add(Pickable(b, s.first, s.second, px, 3f, n < 4, 0xFFD8CCB8.toInt()))
            if (++n >= 20) break
        }
    }
}
