package com.emotionalleaves.universe.render

import android.opengl.GLES30
import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import com.emotionalleaves.universe.core.smoothstep
import com.emotionalleaves.universe.engine.AsteroidDb
import com.emotionalleaves.universe.engine.Body
import com.emotionalleaves.universe.engine.MpcAsteroid
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Every known asteroid (the Minor Planet Center list, ~1.5 million). The elements sit in one GPU
 * buffer and the vertex shader moves each asteroid along its orbit. A background pass works out
 * positions on the CPU every few seconds, for taps and for drawing the ones right next to you.
 */
class AsteroidCloudLayer : Layer {
    private var db: AsteroidDb? = null
    private var vao = 0
    private var vbo = 0
    private var uploadedFloats = 0
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "asteroid-positions").also { it.priority = Thread.MIN_PRIORITY } }
    @Volatile private var busy = false
    @Volatile private var posAu = FloatArray(0)
    @Volatile private var posJd = Double.NaN
    @Volatile private var near = IntArray(0)
    private var lastWall = 0L
    private var zoomOut = 0f

    /** True once the full list is drawn (the small built-in list then only draws comets). */
    val active get() = db != null && uploadedFloats >= (db?.count ?: 0) * 8

    override fun init(rc: RenderContext) { db = null; vao = 0; vbo = 0; uploadedFloats = 0 }

    private fun visibility(frame: Frame): Double {
        val d = (frame.camPos - UPos.ZERO).length()
        return 1.0 - smoothstep(300 * Units.AU, 3000 * Units.AU, d)
    }

    /** How many to draw: all of them, or the brightest 400,000 on Low quality. */
    private fun drawCount(rc: RenderContext, d: AsteroidDb) = if (rc.engine.settings.quality == 0) minOf(d.count, 400_000) else d.count

    override fun update(frame: Frame, rc: RenderContext) {
        zoomOut = smoothstep(2e9, 6e10, frame.focusDistance).toFloat()
        val d = rc.engine.world.allAsteroids
        if (d !== db) {
            if (vbo != 0) { Gl.deleteVao(vao); Gl.deleteBuffer(vbo); vao = 0; vbo = 0 }
            db = d
            uploadedFloats = 0
            posAu = FloatArray(0); near = IntArray(0); posJd = Double.NaN
            if (d == null) return
            vao = Gl.genVao(); vbo = Gl.genBuffer()
            GLES30.glBindVertexArray(vao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, d.count * 32, null, GLES30.GL_STATIC_DRAW)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, 32, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, 32, 16)
            GLES30.glBindVertexArray(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        }
        val db = db ?: return
        // Upload ~6 MB per frame so the first appearance doesn't freeze the screen.
        if (uploadedFloats < db.count * 8) {
            val chunk = minOf(db.count * 8 - uploadedFloats, 1_500_000)
            val buf = db.elements.duplicate()
            buf.position(uploadedFloats)
            buf.limit(uploadedFloats + chunk)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, uploadedFloats * 4, chunk * 4, buf.slice())
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            uploadedFloats += chunk
            Gl.uploadedBytes += chunk * 4L
        }
        if (visibility(frame) <= 0) return
        // CPU positions every few seconds (or when the clock jumps): taps and close-ups need them.
        val now = System.nanoTime()
        val stale = posJd.isNaN() || kotlin.math.abs(frame.jd - posJd) > 2.0 ||
            (now - lastWall > 4_000_000_000L && kotlin.math.abs(frame.jd - posJd) > 1e-3)
        if (busy || !stale) return
        busy = true
        lastWall = now
        val jd = frame.jd
        val cam = (frame.camPos - UPos.ZERO) / Units.AU
        val n = drawCount(rc, db)
        worker.execute {
            try {
                // Written in place: a tap mid-update may see a few positions from the last pass, which is harmless.
                val out = if (posAu.size == n * 3) posAu else FloatArray(n * 3)
                val dt = jd - db.jdRef
                val close = ArrayList<Int>()
                for (i in 0 until n) {
                    db.positionAu(i, dt, out, i * 3)
                    val dx = out[i * 3] - cam.x; val dy = out[i * 3 + 1] - cam.y; val dz = out[i * 3 + 2] - cam.z
                    if (dx * dx + dy * dy + dz * dz < 0.02 * 0.02 && close.size < 64) close.add(i)
                }
                posAu = out
                posJd = jd
                near = close.toIntArray()
            } catch (e: Throwable) {
                com.emotionalleaves.universe.engine.ErrorLog.record("asteroid positions", e)
            } finally {
                busy = false
            }
        }
    }

    private fun limit(rc: RenderContext) = 7.2f + 6.3f * zoomOut + rc.engine.settings.starBrightness

    override fun drawOpaque(frame: Frame, rc: RenderContext) {
        val db = db ?: return
        if (visibility(frame) <= 0) return
        val sun = frame.rel(UPos.ZERO)
        var began = false
        for (i in near) {
            val b = rc.engine.world.asteroidBody(i) ?: continue
            val rel = frame.rel(b.position(frame.jd))
            val r = b.radius
            val px = frame.pixelSize(r, rel.length())
            if (px < 0.7) continue
            if (!began) { rc.drawer.beginOpaque(); began = true }
            val m = Material(Material.ROCKY, floatArrayOf(0.5f, 0.46f, 0.42f), floatArrayOf(0.32f, 0.3f, 0.28f),
                floatArrayOf(0.62f, 0.58f, 0.52f), seed = (i % 997) / 9f, ambient = 0.03f)
            rc.drawer.sphere(frame, rel, r, Quatd.axisAngle(Vec3d.Z, frame.jd * 2.0 % (2 * Math.PI)), m, sun - rel,
                if (px < 40) rc.programs.lowSphereMesh else rc.programs.sphereMesh)
        }
        if (began) rc.drawer.end()
        if (db.count == 0) return
    }

    override fun drawTransparent(frame: Frame, rc: RenderContext) {
        val db = db ?: return
        val vis = visibility(frame)
        if (vis <= 0 || vao == 0 || uploadedFloats < 8) return
        val all = minOf(drawCount(rc, db), uploadedFloats / 8)
        // Zoomed out, every asteroid adds a little light so the belt reads as a haze. Each dot needs at
        // least ~1/255 of light to register, so when the belt is small on screen only the brightest
        // part of the list is drawn (it is sorted brightest first) to keep the haze from burning white.
        val sunDist = (frame.camPos - UPos.ZERO).length()
        val beltPx = if (sunDist < 4 * Units.AU) 1e5 else frame.pixelSize(3.0 * Units.AU, sunDist)
        val area = minOf(Math.PI * beltPx * beltPx * 0.6, frame.width.toDouble() * frame.height)
        val target = 0.3 * zoomOut
        val minAlpha = 0.006
        val n = if (zoomOut <= 0f) all else minOf(all.toDouble(), maxOf(60_000.0, target * area / minAlpha)).toInt()
        val floor = if (zoomOut <= 0f) 0f else (target * area / n).coerceIn(minAlpha, 0.1).toFloat()
        val p = rc.programs.kepler
        rc.drawer.beginAdditive()
        p.use()
        p.setMat4("uViewProj", frame.viewProj)
        p.set1f("uLogF", frame.logF)
        val cam = (frame.camPos - UPos.ZERO) / Units.AU
        p.set3f("uCamAu", cam.x.toFloat(), cam.y.toFloat(), cam.z.toFloat())
        p.set1f("uDt", (frame.jd - db.jdRef).toFloat())
        p.set1f("uMagLimit", limit(rc))
        p.set1f("uSizeScale", 2.2f)
        p.set1f("uMinSize", 1.1f)
        p.set1f("uMaxSize", 5f)
        p.set1f("uAlpha", (0.9 * vis).toFloat())
        p.set1f("uFloor", floor)
        p.set1f("uFadeNearAu", 0.002f)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, n)
        GLES30.glBindVertexArray(0)
        rc.drawer.end()
    }

    override fun pickAt(frame: Frame, rc: RenderContext, x: Float, y: Float, reach: Float): Body? {
        val db = db ?: return null
        val pos = posAu
        if (visibility(frame) <= 0 || pos.isEmpty()) return null
        val n = pos.size / 3
        val cam = (frame.camPos - UPos.ZERO) / Units.AU
        val lim = limit(rc)
        var best = -1
        var bestScore = Double.MAX_VALUE
        for (i in 0 until n) {
            val dx = pos[i * 3] - cam.x; val dy = pos[i * 3 + 1] - cam.y; val dz = pos[i * 3 + 2] - cam.z
            val d2 = dx * dx + dy * dy + dz * dz
            val px0 = pos[i * 3]; val py0 = pos[i * 3 + 1]; val pz0 = pos[i * 3 + 2]
            val r = sqrt((px0 * px0 + py0 * py0 + pz0 * pz0).toDouble())
            val m = db.h(i) + 5 * log10(max(r, 0.05)) + 2.5 * log10(max(d2, 1e-18))
            // Zoomed out, faint ones can still be tapped (they are drawn as a haze).
            if (m > lim + 0.5 + 6 * zoomOut) continue
            val s = frame.project(Vec3d(dx, dy, dz) * Units.AU) ?: continue
            val px = hypot((s.first - x).toDouble(), (s.second - y).toDouble())
            if (px > reach) continue
            val score = px + m
            if (score < bestScore) { bestScore = score; best = i }
        }
        return if (best >= 0) rc.engine.world.asteroidBody(best) else null
    }

    override fun collect(frame: Frame, rc: RenderContext, out: MutableList<Pickable>) {
        val sel = rc.engine.selected as? MpcAsteroid ?: return
        if (visibility(frame) <= 0) return
        frame.project(frame.rel(sel.position(frame.jd)))?.let { out.add(Pickable(sel, it.first, it.second, 6f, 6f, true, 0xFFE8D8C0.toInt())) }
    }
}
