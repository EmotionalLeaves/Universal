package com.emotionalleaves.universe.render

import android.opengl.GLES30
import com.emotionalleaves.universe.core.Mat4
import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Vec3d
import com.emotionalleaves.universe.core.smoothstep
import com.emotionalleaves.universe.engine.Body
import com.emotionalleaves.universe.engine.PlanetBody
import com.emotionalleaves.universe.engine.SolarBody
import com.emotionalleaves.universe.engine.TerrainModel
import com.emotionalleaves.universe.engine.TileBuilder
import com.emotionalleaves.universe.engine.TileKey
import com.emotionalleaves.universe.engine.TileMesh
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.acos
import kotlin.math.max

/**
 * Walkable ground: a cube-sphere quadtree of terrain tiles that refines around the camera,
 * down to tiles a few metres across. Tiles are built on worker threads and uploaded a few per frame.
 */
class TerrainLayer : Layer {
    private class GpuTile(val key: TileKey, val vao: Int, val vbo: Int, val centerLocal: Vec3d, val radius: Double) {
        var lastUsed = 0L
    }

    private var bodyId: String? = null
    private var model: TerrainModel? = null
    private val tiles = HashMap<TileKey, GpuTile>()
    private val requested = HashSet<TileKey>()
    private val done = ConcurrentLinkedQueue<TileMesh>()
    private val pool = Executors.newFixedThreadPool(2)
    private val inFlight = AtomicInteger(0)
    private var ibo = 0
    private var indexCount = 0
    private var frameNo = 0L
    private var generation = 0

    /** Sky colour and fog for the current frame (read by the renderer). */
    var skyColor = floatArrayOf(0f, 0f, 0f)
    var daylight = 0f
    /** Detail: how early tiles split (Low 1.5, Medium 2.2, High 3.0). */
    var splitFactor = 2.2

    override fun init(rc: RenderContext) {
        tiles.clear()
        requested.clear()
        done.clear()
        val idx = TileBuilder.indices()
        ibo = Gl.genBuffer()
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        Gl.bufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.size * 4, Gl.intBuffer(idx), GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        indexCount = idx.size
        bodyId = null
        model = null
    }

    /** Which solid body the camera is close enough to walk on (its target, if any). */
    private fun candidate(frame: Frame, rc: RenderContext): Body? {
        // While landing, prepare the ground of the world being flown to.
        val t = synchronized(rc.engine.camera) { rc.engine.camera.zoomTarget() } ?: return null
        if (t !is SolarBody && t !is PlanetBody) return null
        val d = (t.position(frame.jd) - frame.camPos).length()
        return if (d < t.radius * 2.5) t else null
    }

    override fun update(frame: Frame, rc: RenderContext) {
        frameNo++
        val world = rc.engine.world
        val body = candidate(frame, rc)
        val m = body?.let { world.terrainModel(it) }
        if (m == null || body?.id != bodyId) {
            if (bodyId != null) clear()
            if (m != null && body != null) {
                bodyId = body.id
                model = m
            } else {
                world.terrainBodyId = null
                daylight = 0f
                skyColor = floatArrayOf(0f, 0f, 0f)
                return
            }
        }
        // Upload finished tiles.
        var uploads = 0
        while (uploads < 8) {
            val t = done.poll() ?: break
            if (t.key.face < 0) continue
            upload(t)
            uploads++
        }
        // Only replace the plain sphere once the six root tiles are ready.
        val roots = (0 until 6).map { TileKey(it, 0, 0, 0) }
        for (r in roots) request(r, m!!)
        world.terrainBodyId = if (roots.all { tiles.containsKey(it) }) bodyId else null
        evict()
        updateSky(frame, rc, body!!)
    }

    private fun clear() {
        for (t in tiles.values) { Gl.deleteVao(t.vao); Gl.deleteBuffer(t.vbo) }
        tiles.clear()
        requested.clear()
        done.clear()
        generation++
        bodyId = null
        model = null
    }

    private fun request(key: TileKey, m: TerrainModel) {
        if (tiles.containsKey(key) || key in requested) return
        if (inFlight.get() > 24) return
        requested.add(key)
        inFlight.incrementAndGet()
        val gen = generation
        pool.execute {
            try {
                val mesh = TileBuilder.build(m, key)
                if (gen == generation) done.add(mesh)
            } catch (_: Throwable) {
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private fun upload(t: TileMesh) {
        requested.remove(t.key)
        if (tiles.containsKey(t.key)) return
        val vao = Gl.genVao()
        val vbo = Gl.genBuffer()
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        Gl.bufferData(GLES30.GL_ARRAY_BUFFER, t.vertices.size * 4, Gl.floatBuffer(t.vertices), GLES30.GL_STATIC_DRAW)
        val stride = 8 * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, 24)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBindVertexArray(0)
        tiles[t.key] = GpuTile(t.key, vao, vbo, t.centerLocal, t.radiusM).also { it.lastUsed = frameNo }
    }

    private fun evict() {
        if (tiles.size < 700) return
        val old = tiles.values.filter { frameNo - it.lastUsed > 120 && it.key.level > 0 }.sortedBy { it.lastUsed }
        for (t in old.take(tiles.size - 600)) {
            Gl.deleteVao(t.vao); Gl.deleteBuffer(t.vbo)
            tiles.remove(t.key)
        }
    }

    override fun drawOpaque(frame: Frame, rc: RenderContext) {
        val m = model ?: return
        val id = bodyId ?: return
        if (rc.engine.world.terrainBodyId != id) return
        // The world being orbited, or the one being landed on (otherwise it vanishes during the descent).
        val body = synchronized(rc.engine.camera) { rc.engine.camera.zoomTarget() } ?: return
        if (body.id != id) return
        val q = rc.engine.world.orientationOf(body, frame.jd) ?: return
        val bodyRel = frame.rel(body.position(frame.jd))
        val camLocal = q.conjugate().rotate(-bodyRel)
        val selected = ArrayList<GpuTile>(256)
        for (f in 0 until 6) visit(TileKey(f, 0, 0, 0), m, camLocal, selected)

        val p = rc.programs.terrain
        p.use()
        p.setMat4("uViewProj", frame.viewProj)
        p.set1f("uLogF", frame.logF)
        val rot3 = Mat4.rotation3(q)
        p.setMat3("uRot", rot3)
        val (tex, colA, colB, colC) = look(body, rc)
        if (tex != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            p.set1i("uTex", 0)
            p.set1i("uHasTex", 1)
        } else p.set1i("uHasTex", 0)
        p.set3f("uColorA", colA[0], colA[1], colA[2])
        p.set3f("uColorB", colB[0], colB[1], colB[2])
        p.set3f("uColorC", colC[0], colC[1], colC[2])
        p.set1i("uFlavor", m.flavor)
        p.set3f("uFogColor", skyColor[0], skyColor[1], skyColor[2])
        p.set1f("uFogDensity", fogDensity)
        p.set1f("uSeed", 0f)
        val light = lightFrom(body, frame, rc) - bodyRel
        val l = light.normalized()
        p.set3f("uLightDir", l.x.toFloat(), l.y.toFloat(), l.z.toFloat())

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        GLES30.glDisable(GLES30.GL_BLEND)
        for (t in selected) {
            t.lastUsed = frameNo
            val center = bodyRel + q.rotate(t.centerLocal)
            p.setMat4("uModel", Mat4.model(q, 1.0, center))
            p.set3f("uCenterLocal", t.centerLocal.x.toFloat(), t.centerLocal.y.toFloat(), t.centerLocal.z.toFloat())
            GLES30.glBindVertexArray(t.vao)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
    }

    /** Chooses the tiles to draw: finer near the camera, only where all four children are ready. */
    private fun visit(key: TileKey, m: TerrainModel, camLocal: Vec3d, out: MutableList<GpuTile>) {
        val dir = key.centerDir()
        val size = TileBuilder.tileSizeM(m, key)
        val camDist = camLocal.length()
        // Horizon culling: skip tiles hidden behind the curve of the planet.
        val camDir = camLocal / camDist
        val horizon = acos((m.radius * 0.995 / max(camDist, m.radius)).coerceIn(-1.0, 1.0))
        val angular = size / m.radius * 0.9 + 0.02
        val ang = acos(dir.dot(camDir).coerceIn(-1.0, 1.0))
        if (key.level > 0 && ang > horizon + angular) return
        val tilePoint = dir * m.radius
        val d = (camLocal - tilePoint).length()
        val split = key.level < m.maxLevel && d < size * splitFactor
        if (split) {
            val kids = key.children()
            if (kids.all { tiles.containsKey(it) }) {
                for (k in kids) visit(k, m, camLocal, out)
                return
            }
            for (k in kids) request(k, m)
        }
        val t = tiles[key]
        if (t != null) out.add(t) else request(key, m)
    }

    private data class LookParts(val tex: Int, val a: FloatArray, val b: FloatArray, val c: FloatArray)

    private fun look(body: Body, rc: RenderContext): LookParts = when (body) {
        is SolarBody -> LookParts(
            body.look.texture?.let { rc.textures.get(it) } ?: 0,
            Material.rgb(body.look.colorA), Material.rgb(body.look.colorB), Material.rgb(body.look.colorC),
        )
        is PlanetBody -> LookParts(0, Material.rgb(body.palette[0]), Material.rgb(body.palette[1]), Material.rgb(body.palette[2]))
        else -> LookParts(0, floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(0.3f, 0.3f, 0.3f), floatArrayOf(0.7f, 0.7f, 0.7f))
    }

    private fun lightFrom(body: Body, frame: Frame, rc: RenderContext): Vec3d = when (body) {
        is PlanetBody -> frame.rel(body.star.pos)
        else -> frame.rel(UPos.ZERO)
    }

    private var fogDensity = 0f

    /** Sky colour and haze from the atmosphere above you (blue on Earth, butterscotch on Mars...). */
    private fun updateSky(frame: Frame, rc: RenderContext, body: Body) {
        val (atmoColor, atmoHeight) = when (body) {
            is SolarBody -> if (body.look.atmosphereStrength > 0) Material.rgb(body.look.atmosphere) to when (body.id) {
                "sol:earth" -> 100_000.0
                "sol:mars" -> 45_000.0
                "sol:titan" -> 300_000.0
                else -> 60_000.0
            } else null to 0.0
            is PlanetBody -> if (body.hasAtmosphere) Material.rgb(body.palette[2]) to body.radius * 0.015 else null to 0.0
            else -> null to 0.0
        }
        val bodyRel = frame.rel(body.position(frame.jd))
        val up = (-bodyRel).normalized()
        val alt = bodyRel.length() - body.radius
        if (atmoColor == null || alt > atmoHeight) {
            daylight = 0f
            skyColor = floatArrayOf(0f, 0f, 0f)
            fogDensity = 0f
            return
        }
        val sunDir = (lightFrom(body, frame, rc) - bodyRel).normalized()
        val elev = up.dot(sunDir)
        val day = smoothstep(-0.18, 0.12, elev)
        val thick = 1.0 - smoothstep(0.0, atmoHeight, alt)
        val sunset = (1.0 - smoothstep(0.0, 0.35, kotlin.math.abs(elev))) * thick
        val base = atmoColor
        val r = (base[0] * 0.55 * day + 0.9 * sunset * 0.35).toFloat()
        val g = (base[1] * 0.6 * day + 0.45 * sunset * 0.35).toFloat()
        val b = (base[2] * 0.75 * day + 0.25 * sunset * 0.35).toFloat()
        skyColor = floatArrayOf(r * thick.toFloat(), g * thick.toFloat(), b * thick.toFloat())
        daylight = (day * thick).toFloat()
        fogDensity = (thick * 1.0 / (atmoHeight * 0.6)).toFloat() * (0.3f + 0.7f * daylight)
        if (fogDensity.isNaN()) fogDensity = 0f
    }
}
