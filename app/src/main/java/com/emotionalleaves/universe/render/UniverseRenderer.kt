package com.emotionalleaves.universe.render

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import com.emotionalleaves.universe.core.Mat4
import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.core.Rng
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import com.emotionalleaves.universe.engine.Engine
import com.emotionalleaves.universe.ui.Hud
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class UniverseRenderer(private val context: Context, private val engine: Engine) : GLSurfaceView.Renderer {
    private lateinit var programs: Programs
    private var width = 1
    private var height = 1
    private var lastTime = 0.0
    private var frames = 0
    private var fpsTimer = 0.0
    private var frameTimeSum = 0.0

    private var backgroundStars: PointBatch? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        programs = Programs()
        backgroundStars = buildBackgroundStars()
        lastTime = now()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val t0 = now()
        val dt = (t0 - lastTime).coerceIn(0.0, 0.1)
        lastTime = t0
        engine.update(dt, t0)

        val cam = engine.camera
        val camPos: UPos
        val orientation: Quatd
        val focusDist: Double
        synchronized(cam) {
            camPos = cam.position()
            orientation = cam.orientation
            focusDist = cam.distance
        }
        val fovY = Math.toRadians(60.0)
        val proj = Mat4.perspectiveInfinite(fovY, width.toDouble() / height, 0.01)
        val viewProj = Mat4.multiply(proj, Mat4.viewRotation(orientation))
        val frame = Frame(camPos, orientation, viewProj, width, height, fovY, engine.jd, t0, focusDist)

        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClearDepthf(1f)
        GLES30.glDepthMask(true)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        drawPoints(frame)
        drawDemoPlanet(frame)

        // Stats
        frames++
        frameTimeSum += now() - t0
        fpsTimer += dt
        if (fpsTimer >= 0.5) {
            val fps = (frames / fpsTimer).toInt()
            val ms = (frameTimeSum / frames * 1000).toFloat()
            val distText = Units.formatDistance(focusDist)
            Hud.post {
                Hud.fps = fps
                Hud.frameMs = ms
                Hud.distanceText = distText
            }
            frames = 0; fpsTimer = 0.0; frameTimeSum = 0.0
        }
    }

    private fun drawPoints(frame: Frame) {
        val batch = backgroundStars ?: return
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        val p = programs.points
        p.use()
        p.setMat4("uViewProj", frame.viewProj)
        p.set1f("uLogF", frame.logF)
        val off = frame.rel(batch.anchor)
        p.set3f("uOffset", off.x.toFloat(), off.y.toFloat(), off.z.toFloat())
        p.set1f("uScale", batch.unit.toFloat())
        p.set1f("uMagLimit", 6.5f)
        p.set1f("uSizeScale", 3.0f)
        p.set1f("uMinSize", 1.5f)
        p.set1f("uMaxSize", 9f)
        p.set1f("uFadeNear", 0f)
        p.set1f("uFadeFar", 0f)
        p.set1f("uAlpha", 1f)
        p.set1f("uHide", -1f)
        batch.draw()
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawDemoPlanet(frame: Frame) {
        val body = engine.demoPlanet
        val rel = frame.rel(body.position(frame.jd))
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        val p = programs.sphere
        p.use()
        p.setMat4("uViewProj", frame.viewProj)
        p.set1f("uLogF", frame.logF)
        val spin = frame.time * 0.05
        val rot = Quatd.axisAngle(Vec3d.Z, spin)
        p.setMat4("uModel", Mat4.model(rot, body.radius, rel))
        p.set1i("uHasTex", 0)
        p.set1i("uHasNight", 0)
        p.set1i("uStyle", 4)
        p.set3f("uColorA", 0.08f, 0.22f, 0.5f)
        p.set3f("uColorB", 0.25f, 0.45f, 0.18f)
        p.set3f("uColorC", 0.6f, 0.55f, 0.45f)
        p.set1f("uSeed", 3.7f)
        val light = Vec3d(1.0, 0.6, 0.4).normalized()
        p.set3f("uLightDir", light.x.toFloat(), light.y.toFloat(), light.z.toFloat())
        p.set1f("uAmbient", 0.06f)
        programs.sphereMesh.draw()
        GLES30.glDisable(GLES30.GL_CULL_FACE)
    }

    /** A sprinkle of distant stars so you can see the view turning (replaced by real stars in Round 5). */
    private fun buildBackgroundStars(): PointBatch {
        val r = Rng(42)
        val n = 6000
        val data = FloatArray(n * 7)
        for (i in 0 until n) {
            val v = r.unitVector() * 1000.0 // 1000 pc away
            val mag = (-1.0 + 7.0 * Math.pow(r.nextDouble(), 0.4)).toFloat()
            data[i * 7] = v.x.toFloat(); data[i * 7 + 1] = v.y.toFloat(); data[i * 7 + 2] = v.z.toFloat()
            val warm = r.nextDouble().toFloat()
            data[i * 7 + 3] = 0.8f + 0.2f * warm; data[i * 7 + 4] = 0.85f; data[i * 7 + 5] = 1f - 0.25f * warm
            // absolute magnitude chosen so apparent magnitude from 1000 pc equals mag
            data[i * 7 + 6] = mag - 10f
        }
        return PointBatch.upload(UPos.ZERO, Units.PC, data)
    }

    private fun now() = SystemClock.elapsedRealtimeNanos() / 1e9
}
