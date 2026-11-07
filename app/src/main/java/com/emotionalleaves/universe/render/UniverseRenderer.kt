package com.emotionalleaves.universe.render

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import com.emotionalleaves.universe.core.Mat4
import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.engine.Body
import com.emotionalleaves.universe.engine.Engine
import com.emotionalleaves.universe.ui.Hud
import com.emotionalleaves.universe.ui.Label
import com.emotionalleaves.universe.ui.LabelOverlay
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.tan

class UniverseRenderer(private val context: Context, private val engine: Engine) : GLSurfaceView.Renderer {
    private var rc: RenderContext? = null
    private val textures = Textures(context)
    private var width = 1
    private var height = 1
    private var lastTime = 0.0
    private var frames = 0
    private var fpsTimer = 0.0
    private var frameTimeSum = 0.0
    private var hudTimer = 0.0

    /** Where labels are drawn (set by the UI). */
    @Volatile var labelOverlay: LabelOverlay? = null

    private val testLayer = TestLayer()
    private val solarLayer = SolarSystemLayer()
    private val starLayer = StarLayer()
    private val systemLayer = SystemLayer()
    private val terrainLayer = TerrainLayer()
    private val galaxyLayer = GalaxyLayer()
    private val deepSkyLayer = DeepSkyLayer()
    private val galaxyCatalogLayer = GalaxyCatalogLayer()
    private val cosmicLayer = CosmicLayer()
    private val layers: List<Layer> = listOf(
        cosmicLayer, galaxyCatalogLayer, galaxyLayer, deepSkyLayer, starLayer, systemLayer, solarLayer, terrainLayer, testLayer,
    )

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        textures.contextLost()
        val programs = Programs()
        val ctx = RenderContext(context, engine, programs, BodyDrawer(programs), textures)
        rc = ctx
        for (l in layers) l.init(ctx)
        lastTime = now()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val ctx = rc ?: return
        // Battery saver: cap at ~30 frames per second.
        if (engine.settings.batterySaver) {
            val wait = 0.033 - (now() - lastTime)
            if (wait > 0.002) Thread.sleep((wait * 1000).toLong())
        }
        val t0 = now()
        val dt = (t0 - lastTime).coerceIn(0.0, 0.1)
        lastTime = t0
        engine.update(dt, t0)
        textures.pump()

        val cam = engine.camera
        val camPos: UPos
        val orientation: Quatd
        val focusDist: Double
        val target: Body?
        synchronized(cam) {
            camPos = cam.position()
            orientation = cam.orientation
            focusDist = cam.distance
            target = cam.target
        }
        val fovY = Math.toRadians(60.0)
        // Near the ground, tilt the view up toward the horizon so you see the landscape.
        var viewOrientation = orientation
        if (target != null && target.radius > 0) {
            val alt = (target.position(engine.jd) - camPos).length() - target.radius
            val f = 1.0 - com.emotionalleaves.universe.core.smoothstep(0.0003, 0.03, alt / target.radius)
            if (f > 0) viewOrientation = orientation * Quatd.axisAngle(com.emotionalleaves.universe.core.Vec3d.X, Math.toRadians(76.0) * f)
        }
        val proj = Mat4.perspectiveInfinite(fovY, width.toDouble() / height, 0.05)
        val viewProj = Mat4.multiply(proj, Mat4.viewRotation(viewOrientation))
        val frame = Frame(camPos, viewOrientation, viewProj, width, height, fovY, engine.jd, t0, focusDist)

        val sky = terrainLayer.skyColor
        GLES30.glClearColor(sky[0], sky[1], sky[2], 1f)
        GLES30.glClearDepthf(1f)
        GLES30.glDepthMask(true)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        testLayer.enabled = engine.settings.showTestObjects
        starLayer.daylight = terrainLayer.daylight
        galaxyLayer.daylight = terrainLayer.daylight
        deepSkyLayer.daylight = terrainLayer.daylight
        galaxyCatalogLayer.daylight = terrainLayer.daylight
        cosmicLayer.daylight = terrainLayer.daylight
        galaxyLayer.particles = when (engine.settings.quality) { 0 -> 20_000; 2 -> 70_000; else -> 40_000 }
        terrainLayer.splitFactor = when (engine.settings.quality) { 0 -> 1.5; 2 -> 3.0; else -> 2.2 }
        solarLayer.showOrbits = engine.settings.showOrbits
        systemLayer.showOrbits = engine.settings.showOrbits
        for (l in layers) l.update(frame, ctx)
        for (l in layers) l.drawOpaque(frame, ctx)
        for (l in layers) l.drawTransparent(frame, ctx)

        // Tap targets and labels
        val picks = ArrayList<Pickable>(256)
        for (l in layers) l.collect(frame, ctx, picks)
        engine.pickables = picks
        engine.takeTap()?.let { tap ->
            var body = engine.pick(tap.x, tap.y)?.body
            if (body == null) {
                for (l in layers.asReversed()) {
                    body = l.pickAt(frame, ctx, tap.x, tap.y, engine.tapReach)
                    if (body != null) break
                }
            }
            engine.applyTap(body, tap.double)
        }
        publishLabels(picks)

        // Stats and HUD (a few times a second)
        frames++
        frameTimeSum += now() - t0
        fpsTimer += dt
        hudTimer += dt
        if (fpsTimer >= 0.5) {
            val fps = (frames / fpsTimer).toInt()
            val ms = (frameTimeSum / frames * 1000).toFloat()
            Hud.post { Hud.fps = fps; Hud.frameMs = ms }
            frames = 0; fpsTimer = 0.0; frameTimeSum = 0.0
        }
        if (hudTimer >= 0.1) {
            hudTimer = 0.0
            publishHud(frame, target, focusDist)
        }
    }

    private fun publishLabels(picks: List<Pickable>) {
        val overlay = labelOverlay ?: return
        if (!engine.settings.showLabels) {
            val sel = engine.selected
            overlay.labels = picks.filter { it.body == sel }.take(1).map {
                Label(it.x, it.y, it.body.name, 0xFFFFD27A.toInt(), true, it.pixelRadius.coerceAtMost(200f))
            }
            overlay.refresh()
            return
        }
        val selected = engine.selected
        val sorted = picks.filter { it.showLabel || it.body == selected }.sortedByDescending { it.priority }
        val out = ArrayList<Label>()
        val taken = ArrayList<FloatArray>()
        val density = context.resources.displayMetrics.density
        for (p in sorted) {
            if (out.size >= 40) break
            val sel = p.body == selected
            // Skip labels that would overlap a more important one.
            val text = p.labelText ?: p.body.name
            val box = floatArrayOf(p.x, p.y - 16 * density, p.x + text.length * 7.5f * density, p.y)
            if (!sel && taken.any { overlaps(it, box) }) continue
            taken.add(box)
            out.add(Label(p.x, p.y, text, if (sel) 0xFFFFD27A.toInt() else p.labelColor, sel, p.pixelRadius.coerceAtMost(200f)))
        }
        overlay.labels = out
        overlay.refresh()
    }

    private fun overlaps(a: FloatArray, b: FloatArray) = a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]

    private fun publishHud(frame: Frame, target: Body?, focusDist: Double) {
        val sel = engine.selected
        val selDist = sel?.let { (it.position(frame.jd) - frame.camPos).length() }
        val selFacts = sel?.facts(frame.jd) ?: emptyList()
        val altitude = if (target != null && target.radius > 0) {
            val d = (target.position(frame.jd) - frame.camPos).length() - target.radius
            "Altitude ${Units.formatDistance(d)}"
        } else ""
        // Scale bar: a round length close to 28% of the screen width at the focus distance.
        val metresPerPx = 2 * focusDist * tan(frame.fovY / 2) / frame.height
        val barMax = metresPerPx * frame.width * 0.28
        val barLen = Units.niceLength(barMax)
        val barFraction = (barLen / metresPerPx / frame.width).toFloat()
        val zoom = engine.zoomFraction()
        val flying = engine.camera.isFlying
        val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date(com.emotionalleaves.universe.core.Astro.epochMillis(frame.jd)))
        val rate = engine.rateLabel()
        val crumb = engine.world.breadcrumb(frame.camPos, target)
        Hud.post {
            Hud.breadcrumb = crumb
            Hud.dateText = date
            Hud.rateText = rate
            Hud.distanceText = Units.formatDistance(focusDist)
            Hud.focusName = target?.name ?: "Empty space"
            Hud.altitudeText = altitude
            Hud.selectedName = sel?.name ?: ""
            Hud.selectedSubtitle = sel?.subtitle ?: ""
            Hud.selectedSource = sel?.source?.label ?: ""
            Hud.selectedDistance = selDist?.let { Units.formatDistance(it) } ?: ""
            Hud.selectedFacts = selFacts
            Hud.scaleBarText = Units.formatDistance(barLen)
            Hud.scaleBarFraction = barFraction
            if (!Hud.sliderDragging) Hud.zoomFraction = zoom
            Hud.flying = flying
        }
    }

    private fun now() = SystemClock.elapsedRealtimeNanos() / 1e9
}
