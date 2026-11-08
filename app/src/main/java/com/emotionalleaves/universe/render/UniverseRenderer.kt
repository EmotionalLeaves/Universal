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
    private val galaxyLayer = GalaxyLayer()
    private val deepSkyLayer = DeepSkyLayer()
    private val galaxyCatalogLayer = GalaxyCatalogLayer()
    private val cosmicLayer = CosmicLayer()
    private val fieldLayer = FieldGalaxyLayer()
    private val exoticLayer = ExoticLayer()
    private val smallBodyLayer = SmallBodyLayer()
    private val remnantLayer = RemnantLayer()
    private val hazeLayer = HazeLayer()
    private val beltLayer = BeltLayer()
    private val rockLayer = RockLayer()
    private val webLodLayer = WebLodLayer()
    private val layers: List<Layer> = listOf(
        cosmicLayer, hazeLayer, webLodLayer, exoticLayer, fieldLayer, galaxyCatalogLayer, galaxyLayer, deepSkyLayer, starLayer, remnantLayer, systemLayer, beltLayer, rockLayer,
        solarLayer, smallBodyLayer, testLayer,
    )

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        textures.contextLost()
        val programs = Programs()
        val ctx = RenderContext(context, engine, programs, BodyDrawer(programs), textures)
        rc = ctx
        for (l in layers) l.init(ctx)
        postFx = try { PostFx().also { it.resize(width, height) } } catch (e: Throwable) { com.emotionalleaves.universe.engine.ErrorLog.record("glow setup", e); null }
        lastTime = now()
        // Point sprites carry the stars and galaxies: log what this GPU allows (a cap of 1 px = no galaxies).
        val range = IntArray(2)
        GLES30.glGetIntegerv(0x846D, range, 0)
        android.util.Log.i("UniverseDiag", "point size range ${range[0]}..${range[1]}")
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
        GLES30.glViewport(0, 0, width, height)
        postFx?.resize(width, height)
    }

    private var postFx: PostFx? = null

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
        val flyingTo: Body?
        synchronized(cam) {
            camPos = cam.position()
            orientation = cam.orientation
            focusDist = cam.distance
            target = cam.target
            flyingTo = if (cam.isFlying) cam.zoomTarget() else null
        }
        this.flyingTo = flyingTo
        val fovY = Math.toRadians(60.0)
        val viewOrientation = orientation
        val proj = Mat4.perspectiveInfinite(fovY, width.toDouble() / height, 0.05)
        val viewProj = Mat4.multiply(proj, Mat4.viewRotation(viewOrientation))
        val frame = Frame(camPos, viewOrientation, viewProj, width, height, fovY, engine.jd, t0, focusDist)

        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClearDepthf(1f)
        GLES30.glDepthMask(true)
        val fx = postFx
        val glow = fx != null && engine.settings.quality > 0 && fx.begin(true)
        if (!glow) fx?.begin(false)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        testLayer.enabled = engine.settings.showTestObjects
        galaxyLayer.particles = when (engine.settings.quality) { 0 -> 20_000; 2 -> 70_000; else -> 40_000 }
        galaxyLayer.maxBlob = when (engine.settings.quality) { 0 -> 14f; 2 -> 28f; else -> 20f }
        solarLayer.showOrbits = engine.settings.showOrbits
        systemLayer.showOrbits = engine.settings.showOrbits
        for (l in layers) safely(l, "update") { l.update(frame, ctx) }
        diagTimer += dt
        val diag = diagTimer >= 3.0
        if (diag) { diagTimer = 0.0; GLES30.glGetError() }
        for (l in layers) safely(l, "drawOpaque") { l.drawOpaque(frame, ctx); if (diag) glCheck(l, "drawOpaque") }
        for (l in layers) safely(l, "drawTransparent") { l.drawTransparent(frame, ctx); if (diag) glCheck(l, "drawTransparent") }
        if (glow) try { fx!!.end(0.75f) } catch (e: Throwable) { com.emotionalleaves.universe.engine.ErrorLog.record("glow", e); postFx = null }
        if (diag) {
            android.util.Log.i("UniverseDiag", "dist=%.3e target=%s fps=%d gpuMB=%.1f lastErr=0x%x galaxies=%d near=%s".format(
                focusDist, target?.name, Hud.fps, Gl.uploadedBytes / 1e6, Gl.lastGlError, engine.world.galaxies.size,
                engine.world.nearGalaxies.joinToString(",") { it.name }))
        }

        lastFrame = frame
        // Tap targets and labels
        val picks = ArrayList<Pickable>(256)
        for (l in layers) safely(l, "collect") { l.collect(frame, ctx, picks) }
        hideBehindWorlds(frame, picks)
        engine.pickables = picks
        engine.takeTap()?.let { tap ->
            var body = engine.pick(tap.x, tap.y)?.body
            if (body == null) {
                for (l in layers.asReversed()) {
                    body = try { l.pickAt(frame, ctx, tap.x, tap.y, engine.tapReach) } catch (e: Exception) {
                        com.emotionalleaves.universe.engine.ErrorLog.record("${l.javaClass.simpleName}.pickAt", e); null
                    }
                    if (body != null) break
                }
            }
            if (body == null) {
                body = try { engine.world.starAlongRay(frame.camPos, frame.rayThrough(tap.x, tap.y)) } catch (e: Exception) {
                    com.emotionalleaves.universe.engine.ErrorLog.record("starAlongRay", e); null
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

    private val solidKinds = setOf(
        com.emotionalleaves.universe.engine.Kind.STAR, com.emotionalleaves.universe.engine.Kind.PLANET,
        com.emotionalleaves.universe.engine.Kind.DWARF_PLANET, com.emotionalleaves.universe.engine.Kind.MOON,
        com.emotionalleaves.universe.engine.Kind.ASTEROID, com.emotionalleaves.universe.engine.Kind.COMET,
    )

    /**
     * A planet filling the view hides what is behind it: drop the labels and tap targets of
     * anything farther away whose point falls on its disc (Rosette Nebula was labelled on Mars).
     */
    private fun hideBehindWorlds(frame: Frame, picks: ArrayList<Pickable>) {
        // Worlds big enough on screen to hide things, plus the one you are orbiting or standing on.
        val walls = LinkedHashMap<String, Body>()
        for (p in picks) if (p.pixelRadius >= 8f && p.body.kind in solidKinds) walls[p.body.id] = p.body
        engine.camera.target?.let { if (it.kind in solidKinds) walls[it.id] = it }
        if (walls.isEmpty()) return
        val centres = walls.values.map { it to (it.position(frame.jd) - frame.camPos) }
        val hidden = HashMap<String, Boolean>()
        picks.removeAll { p ->
            hidden.getOrPut(p.body.id) {
                val rel = p.body.position(frame.jd) - frame.camPos
                val dist = rel.length()
                if (dist <= 0) return@getOrPut false
                val dir = rel / dist
                centres.any { (w, c) ->
                    if (w.id == p.body.id) return@any false
                    // Does the line of sight pass through the world before reaching the object?
                    val t = c.dot(dir)
                    if (t <= 0 || t >= dist) return@any false
                    val miss2 = c.lengthSq() - t * t
                    miss2 < w.radius * w.radius * 0.998
                }
            }
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
        val named = HashSet<String>()
        val density = context.resources.displayMetrics.density
        val sliderBox = floatArrayOf(width - 150 * density, height / 2f - 175 * density, width.toFloat(), height / 2f + 175 * density)
        for (p in sorted) {
            if (out.size >= 28) break   // fewer names: a calmer, less cluttered view
            // Several layers may offer the same object (e.g. the selection): label it once.
            if (!named.add(p.body.id)) continue
            val sel = p.body == selected
            // Skip labels that would overlap a more important one.
            val text = p.labelText ?: p.body.name
            val box = floatArrayOf(p.x, p.y - 16 * density, p.x + text.length * 7.5f * density, p.y)
            if (!sel && taken.any { overlaps(it, box) }) continue
            // Keep the zoom scale on the right readable (and its labels tappable).
            if (!sel && overlaps(sliderBox, box)) continue
            taken.add(box)
            out.add(Label(p.x, p.y, text, if (sel) 0xFFFFD27A.toInt() else p.labelColor, sel, p.pixelRadius.coerceAtMost(200f)))
        }
        // Selected object off screen (or behind you): an arrow at the edge shows where it is.
        if (selected != null) {
            val f = lastFrame
            if (f != null) {
                val rel = f.rel(selected.position(f.jd))
                val c = f.orientation.conjugate().rotate(rel)
                val onScreen = f.project(rel)?.let { it.first in 0f..f.width.toFloat() && it.second in 0f..f.height.toFloat() } ?: false
                if (!onScreen && c.lengthSq() > 0) {
                    out.add(Label(c.x.toFloat(), (-c.y).toFloat(), selected.name, 0xFFFFD27A.toInt(), true, edge = true))
                }
            }
        }
        overlay.labels = out
        overlay.refresh()
    }

    private var lastFrame: Frame? = null

    private fun overlaps(a: FloatArray, b: FloatArray) = a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]

    /** What the camera is flying to right now (null when not flying), for the status panel. */
    private var flyingTo: Body? = null
    private var aboutFor: String? = null
    private var aboutCache: com.emotionalleaves.universe.engine.Describe.About? = null
    private var storyCache = ""
    private var statsCache: List<Pair<String, String>> = emptyList()

    private fun publishHud(frame: Frame, target: Body?, focusDist: Double) {
        val sel = engine.selected
        if (sel?.id != aboutFor) {
            aboutFor = sel?.id
            aboutCache = sel?.let { try { com.emotionalleaves.universe.engine.Describe.about(it) } catch (_: Exception) { null } }
            storyCache = sel?.let { com.emotionalleaves.universe.engine.Story.of(it, frame.jd) } ?: ""
            statsCache = sel?.let { com.emotionalleaves.universe.engine.Describe.headline(it, frame.jd) } ?: emptyList()
        }
        val about = aboutCache
        val selDist = sel?.let { (it.position(frame.jd) - frame.camPos).length() }
        val selFacts = (sel?.facts(frame.jd) ?: emptyList()).let { f ->
            // Every star can be visited: say how many planets wait there (real or generated).
            if (sel is com.emotionalleaves.universe.engine.StarBody && sel.id != "sol:sun" && f.none { it.first == "Planets" }) {
                val sys = try { engine.world.systemFor(sel) } catch (_: Exception) { null }
                if (sys != null) {
                    val n = sys.planets.size
                    val what = if (n == 0) "none found" else "$n " + (if (sys.planets.all { it.source == com.emotionalleaves.universe.engine.Source.REAL }) "(real)" else "(fly there to see them)")
                    f + ("Planets" to what)
                } else f
            } else f
        }
        val altitude = if (target != null && engine.camera.isSolidTarget()) {
            val rel = frame.camPos - target.position(frame.jd)
            "Altitude ${Units.formatDistance((rel.length() - target.radius).coerceAtLeast(0.0))}"
        } else ""
        // Scale bar: a round length close to 28% of the screen width at the focus distance.
        val metresPerPx = 2 * focusDist * tan(frame.fovY / 2) / frame.height
        val barMax = metresPerPx * frame.width * 0.28
        val barLen = Units.niceLength(barMax)
        val barFraction = (barLen / metresPerPx / frame.width).toFloat()
        val zoom = engine.zoomFraction()
        val auto = engine.autopilot
        val flying = engine.camera.isFlying || auto != null
        // Free flight: the nearest thing you are not already at, for the "Nearest" button.
        val visit = if (engine.freeFlight && auto == null) engine.nearestToVisit() else null
        val visitDist = visit?.let { v -> engine.world.nearestFew.firstOrNull { it.first.id == v.id }?.second }
        val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date(com.emotionalleaves.universe.core.Astro.epochMillis(frame.jd)))
        val rate = engine.rateLabel() + if (!engine.paused && engine.timeRate == 1.0 &&
            kotlin.math.abs(frame.jd - com.emotionalleaves.universe.core.Astro.julianDate(System.currentTimeMillis())) > 0.01) " · not now" else ""
        val crumb = engine.world.breadcrumb(frame.camPos, target)
        val choices = if (sel == null) emptyList() else engine.tapChoices.filter { it.id != sel.id }.map { it.name to it.id }
        val w = engine.world
        val loading = when {
            engine.busy.isNotEmpty() -> engine.busy
            w.realStars == null && !w.starsFailed -> "Loading stars…"
            w.galaxies.size <= 1 -> "Loading galaxies…"
            w.cosmicWeb == null -> "Building the cosmic web…"
            else -> ""
        }
        Hud.post {
            Hud.breadcrumb = crumb
            Hud.dateText = date
            Hud.rateText = rate
            Hud.distanceText = Units.formatDistance(focusDist)
            val going = flyingTo
            val free = engine.freeFlight
            Hud.freeFlight = free
            Hud.suggestFree = !free && focusDist > 3e23
            if (free) {
                Hud.speedText = Units.formatDistance(engine.flightSpeed()) + "/s"
                Hud.warpText = "×" + String.format(java.util.Locale.US, "%,.0f", engine.warpFactors[engine.warp])
                Hud.nearestText = when {
                    auto != null -> "Travelling to ${auto.name}"
                    visit != null -> "Nearest: ${visit.name}, " + Units.formatDistance(visitDist ?: engine.world.nearestDist)
                    else -> "Nothing nearby yet"
                }
                Hud.canVisitNearest = visit != null
            }
            Hud.focusName = if (free) (if (auto != null) "Autopilot" else "Free flight") else target?.name ?: going?.let { "Flying to ${it.name}" } ?: "Empty space"
            Hud.lastName = if (!free && target == null && going == null) (engine.lastTarget?.name ?: "") else ""
            Hud.altitudeText = altitude
            Hud.selectedName = sel?.name ?: ""
            if (Hud.choices != choices) Hud.choices = choices
            Hud.selectedSubtitle = sel?.subtitle ?: ""
            Hud.selectedSource = sel?.source?.label ?: ""
            Hud.selectedDistance = selDist?.let { Units.formatDistance(it) } ?: ""
            Hud.selectedLight = selDist?.let { if (it > 3e8) "light takes ${Units.lightTime(it)}" else "" } ?: ""
            Hud.loading = loading
            Hud.selectedFacts = selFacts
            // A Wikipedia summary goes under "About"; a generic type explanation is replaced by the story.
            Hud.selectedAbout = about?.takeIf { it.credit.startsWith("Wikipedia") || storyCache.isEmpty() }?.text ?: ""
            Hud.selectedStory = storyCache
            Hud.selectedStats = statsCache
            Hud.selectedKind = sel?.kind?.label?.takeIf { it != "Marker" } ?: ""
            Hud.selectedAboutCredit = about?.credit ?: ""
            Hud.scaleBarText = Units.formatDistance(barLen)
            Hud.scaleBarFraction = barFraction
            if (!Hud.sliderDragging) Hud.zoomFraction = zoom
            Hud.flying = flying
        }
    }

    /** One misbehaving layer must not take the whole app down: log it and keep drawing the rest. */
    private inline fun safely(l: Layer, what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            com.emotionalleaves.universe.engine.ErrorLog.record("${l.javaClass.simpleName}.$what", e)
            rc?.drawer?.end()
        }
    }

    private var diagTimer = 0.0

    private fun glCheck(l: Layer, what: String) {
        val e = GLES30.glGetError()
        if (e != GLES30.GL_NO_ERROR) {
            Gl.lastGlError = e
            com.emotionalleaves.universe.engine.ErrorLog.record("${l.javaClass.simpleName}.$what", RuntimeException("GL error 0x${Integer.toHexString(e)}"))
        }
    }

    private fun now() = SystemClock.elapsedRealtimeNanos() / 1e9
}
