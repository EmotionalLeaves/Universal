package com.emotionalleaves.universe.engine

import android.content.Context
import android.os.SystemClock
import com.emotionalleaves.universe.core.Astro
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import com.emotionalleaves.universe.render.Pickable
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.exp

/** Owns the simulation state: camera, time, world and selection. Rendering lives in UniverseRenderer. */
class Engine(val context: Context) {
    val camera = CameraController()
    val settings = Settings(context)
    val world = World(this)
    val packs = PackManager(context, world, settings)
    val search = SearchIndex(world)
    val bookmarks = Bookmarks(context)
    private val bg = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Builds the search index in the background (first time the search screen opens). */
    fun prepareSearch(done: () -> Unit) {
        bg.execute {
            if (!search.ready() || world.searchStale) {
                world.searchStale = false
                search.build()
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post(done)
        }
    }

    /** Selects and flies to the object with [id] (search results, bookmarks). */
    fun goTo(id: String) {
        bg.execute {
            val b = world.find(id) ?: return@execute
            selected = b
            world.rememberGenerated(b)
            flyTo(b)
        }
    }

    /** Saves the current view (and the selected or focused object) as a bookmark. */
    fun addBookmark(): String {
        val (focus, dist, q, target) = synchronized(camera) { Quad(camera.focus, camera.distance, camera.orientation, camera.target) }
        val body = selected ?: target
        val name = body?.name ?: "View at ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}"
        val useBody = body != null && body == target
        bookmarks.add(Bookmark(name, body?.id, if (useBody) focus else (body?.position(jd) ?: focus),
            if (useBody) dist else (body?.viewDistance() ?: dist), q, System.currentTimeMillis()))
        body?.let { world.rememberGenerated(it) }
        return name
    }

    private data class Quad(val a: UPos, val b: Double, val c: com.emotionalleaves.universe.core.Quatd, val d: Body?)

    fun openBookmark(b: Bookmark) {
        bg.execute {
            val body = b.bodyId?.let { world.find(it) }
            selected = body
            synchronized(camera) {
                if (body != null) camera.flyTo(body, body.position(jd), b.distance, nowSeconds())
                else camera.flyTo(null, b.focus, b.distance, nowSeconds())
                camera.lookFrom(b.orientation.rotate(Vec3d.Z), b.orientation.rotate(Vec3d.Y))
            }
        }
    }

    /** Simulation time as a Julian date. */
    @Volatile var jd: Double = Astro.julianDate(System.currentTimeMillis())
    /** Simulated seconds per real second. */
    @Volatile var timeRate: Double = 1.0
    @Volatile var paused: Boolean = false

    @Volatile var selected: Body? = null
    /** Tap targets from the last frame (written by the renderer). */
    @Volatile var pickables: List<Pickable> = emptyList()

    private val density = context.resources.displayMetrics.density

    init {
        world.start()
        val home = world.homeBody()
        camera.jumpTo(home, home.position(jd), home.viewDistance())
    }

    fun nowSeconds() = SystemClock.elapsedRealtimeNanos() / 1e9

    fun update(dt: Double, now: Double) {
        if (!paused) jd += dt * timeRate / 86400.0
        synchronized(camera) {
            val before = camera.snapshot()
            surfaceFollow()
            camera.update(dt, now) { it.position(jd) }
            // Never let the camera end up inside a planet: undo the move instead.
            val cam = camera.position()
            for (b in world.solidBodiesNear(cam, jd)) {
                val c = b.position(jd)
                val minR = b.radius + world.surfaceHeight(b, cam - c) + 0.3
                if ((cam - c).length() < minR && !camera.isFlying) {
                    camera.restore(before)
                    break
                }
            }
        }
        world.update(this, dt)
    }

    fun onUserInteraction() {}

    private var lastSpinBody: String? = null
    private var lastSpin: com.emotionalleaves.universe.core.Quatd? = null

    /** Keeps the camera turning with a nearby planet, and sets ground height and walk mode. Holds the camera lock. */
    private fun surfaceFollow() {
        val t = camera.target
        val spin = when (t) {
            is SolarBody -> t.orientation(jd)
            is PlanetBody -> t.spin(jd)
            else -> null
        }
        if (t != null && spin != null && camera.distance < t.radius * 20) {
            val prev = lastSpin
            if (prev != null && lastSpinBody == t.id) camera.rotateFrame(spin * prev.conjugate())
            lastSpin = spin
            lastSpinBody = t.id
        } else {
            lastSpin = null
            lastSpinBody = null
        }
        if (t != null && t.radius > 0) {
            val center = t.position(jd)
            val camRel = camera.position() - center
            camera.surfaceExtra = world.surfaceHeight(t, camRel).coerceAtLeast(-t.radius * 0.5)
            val alt = camera.altitude().coerceAtLeast(0.1)
            camera.rotateScale = (alt / t.radius * 1.6).coerceIn(2e-8, 1.0)
            camera.walkMode = alt < t.radius * 0.002
        } else {
            camera.surfaceExtra = 0.0
            camera.rotateScale = 1.0
            camera.walkMode = false
        }
    }

    // ------------------------------------------------------------------ time controls

    private val rateSteps = doubleArrayOf(
        -31_557_600.0, -2_629_800.0, -604_800.0, -86_400.0, -3_600.0, -60.0, -1.0,
        1.0, 60.0, 3_600.0, 86_400.0, 604_800.0, 2_629_800.0, 31_557_600.0,
    )

    fun faster() {
        paused = false
        timeRate = rateSteps.firstOrNull { it > timeRate + 1e-9 } ?: timeRate
    }

    fun slower() {
        paused = false
        timeRate = rateSteps.lastOrNull { it < timeRate - 1e-9 } ?: timeRate
    }

    fun togglePause() {
        paused = !paused
    }

    /** Back to the real current time, running at normal speed. */
    fun timeNow() {
        jd = Astro.julianDate(System.currentTimeMillis())
        timeRate = 1.0
        paused = false
    }

    fun setDate(epochMillis: Long) {
        jd = Astro.julianDate(epochMillis)
    }

    fun rateLabel(): String {
        if (paused) return "Paused"
        val r = timeRate
        val a = kotlin.math.abs(r)
        val unit = when {
            a >= 31_557_600.0 -> "${(a / 31_557_600.0).toInt()} year"
            a >= 2_629_800.0 -> "${(a / 2_629_800.0).toInt()} month"
            a >= 604_800.0 -> "${(a / 604_800.0).toInt()} week"
            a >= 86_400.0 -> "${(a / 86_400.0).toInt()} day"
            a >= 3_600.0 -> "${(a / 3_600.0).toInt()} hour"
            a >= 60.0 -> "${(a / 60.0).toInt()} min"
            else -> "real time"
        }
        return when {
            a == 1.0 && r > 0 -> "Real time"
            r < 0 -> "◀ $unit / s"
            else -> "$unit / s"
        }
    }

    class Tap(val x: Float, val y: Float, val double: Boolean)

    @Volatile private var pendingTap: Tap? = null

    /** Single tap: select what is under the finger (resolved on the next frame). */
    fun onTap(x: Float, y: Float) {
        pendingTap = Tap(x, y, false)
    }

    /** Double tap: fly to what is under the finger. */
    fun onDoubleTap(x: Float, y: Float) {
        pendingTap = Tap(x, y, true)
    }

    fun takeTap(): Tap? {
        val t = pendingTap
        pendingTap = null
        return t
    }

    /** Called by the renderer with what was found under a tap. */
    fun applyTap(body: Body?, double: Boolean) {
        if (body != null && body.source == Source.GENERATED) bg.execute { world.rememberGenerated(body) }
        if (double) {
            if (body != null) { selected = body; flyTo(body) }
        } else {
            selected = body
        }
    }

    val tapReach: Float get() = 36f * density

    fun pick(x: Float, y: Float): Pickable? {
        val reach = 36f * density
        var best: Pickable? = null
        var bestScore = Double.MAX_VALUE
        for (p in pickables) {
            val d = hypot((p.x - x).toDouble(), (p.y - y).toDouble())
            val edge = (d - p.pixelRadius).coerceAtLeast(0.0)
            if (edge > reach) continue
            val score = edge - p.priority * 4.0
            if (score < bestScore) { bestScore = score; best = p }
        }
        return best
    }

    fun flyTo(body: Body) {
        synchronized(camera) {
            camera.flyTo(body, body.position(jd), body.viewDistance(), nowSeconds())
        }
    }

    fun flyToSelected() {
        selected?.let { flyTo(it) }
    }

    fun clearSelection() {
        selected = null
    }

    /** Zoom slider on a log scale of height above the ground: 0 = standing, 1 = whole universe. */
    fun zoomFraction(): Float = synchronized(camera) {
        val lo = ln(1.7); val hi = ln(camera.maxDistance())
        ((ln(camera.altitude().coerceAtLeast(1.7)) - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
    }

    fun setZoomFraction(f: Float) {
        synchronized(camera) {
            val lo = ln(1.7); val hi = ln(camera.maxDistance())
            camera.cancelFlight()
            camera.setDistance(camera.surfaceRadius() + exp(lo + (hi - lo) * f.coerceIn(0f, 1f)))
        }
    }

    /** Returns to the default view. */
    fun goHome() {
        val home = world.homeBody()
        selected = home
        flyTo(home)
    }

    fun distanceFromCamera(body: Body): Double = synchronized(camera) {
        (body.position(jd) - camera.position()).length()
    }

    companion object {
        val TEN_GLY = 1e10 * Units.LY
        fun direction(raDeg: Double, decDeg: Double): Vec3d = Vec3d.fromRaDec(raDeg, decDeg)
        val ORIGIN = UPos.ZERO
    }
}
