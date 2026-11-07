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
            try {
                val b = world.find(id) ?: return@execute
                selected = b
                world.rememberGenerated(b)
                flyTo(b)
            } catch (e: Exception) {
                ErrorLog.record("goTo($id)", e)
            }
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
        ErrorLog.init(context)
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
            // Never let the camera end up inside a planet. Over the body you are orbiting, lift the
            // camera above the ground (so you can walk uphill); for any other body, undo the move.
            if (!camera.isFlying) {
                val cam = camera.position()
                for (b in world.solidBodiesNear(cam, jd)) {
                    val c = b.position(jd)
                    val ground = b.radius + world.surfaceHeight(b, cam - c)
                    if ((cam - c).length() >= ground + 0.3) continue
                    if (b == camera.target) {
                        camera.surfaceExtra = ground - b.radius
                        camera.setDistance(ground + 1.7)
                    } else {
                        camera.restore(before)
                    }
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
        if (t != null) lastTarget = t
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
        if (t != null && camera.isSolidTarget()) {
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
            camera.flyTo(body, body.position(jd), body.viewDistance(), nowSeconds(), arrivalView(body))
        }
    }

    /** For planets and moons: arrive looking at the sunlit side, a little off the sun line. */
    private fun arrivalView(body: Body): com.emotionalleaves.universe.core.Quatd? {
        val light: UPos = when (body) {
            is SolarBody -> if (body.kind == Kind.STAR) return null else UPos.ZERO
            is PlanetBody -> body.star.pos
            else -> return null
        }
        val p = body.position(jd)
        val toLight = (light - p).normalized()
        val side = Vec3d.perpendicular(toLight)
        val camDir = (toLight * kotlin.math.cos(0.6) + side * kotlin.math.sin(0.6)).normalized()
        val up = when (body) {
            is SolarBody -> Vec3d.fromRaDec(body.poleRa, body.poleDec)
            else -> Vec3d.Z
        }
        val q = com.emotionalleaves.universe.core.Quatd.lookRotation(-camDir, up)
        return q
    }

    fun flyToSelected() {
        selected?.let { flyTo(it) }
    }

    /** True if [b] has ground you can stand on. */
    fun canLand(b: Body?): Boolean = when (b) {
        is SolarBody -> b.kind != Kind.STAR && b.id !in setOf("sol:jupiter", "sol:saturn", "sol:uranus", "sol:neptune", "sol:venus") && b.look.style != 1
        is PlanetBody -> b.solid
        else -> false
    }

    /** Flies down to stand on the selected world, below where the camera is now. */
    fun landOnSelected() {
        val b = selected ?: return
        if (!canLand(b)) return
        synchronized(camera) {
            val c = b.position(jd)
            val dir = (camera.position() - c).normalized()
            camera.flyTo(b, c, b.radius + 2.0, nowSeconds())
            camera.lookFrom(dir, Vec3d.perpendicular(dir))
        }
    }

    /** The thing that contains [b]: moon → planet → star → galaxy → universe. */
    fun parentOf(b: Body?): Body? = when (b) {
        null -> null
        is SolarBody -> b.parent ?: world.milkyWay
        is PlanetBody -> b.host ?: b.star
        is StarBody -> if (b.galaxyName == "Milky Way") world.milkyWay else world.galaxyContaining(b.pos) ?: world.universe
        is DsoBody -> world.milkyWay
        is GalaxyBody -> if (b.isMilkyWay) world.universe else world.universe
        is UniverseBody -> null
        else -> world.milkyWay
    }

    /** Last object the camera orbited, so you can get back after drifting into empty space. */
    @Volatile var lastTarget: Body? = null

    /** Re-centre on the selected object, or the last thing you were orbiting. */
    fun recenter() {
        val b = selected ?: lastTarget ?: world.homeBody()
        selected = b
        flyTo(b)
    }

    /** Flies to an object by name (Explore lists). Builds the search index if needed. */
    fun goToName(name: String) {
        bg.execute {
            try {
                if (!search.ready() || world.searchStale) { world.searchStale = false; search.build() }
                val hits = search.search(name, 5)
                val hit = hits.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: hits.firstOrNull() ?: return@execute
                val b = world.find(hit.id) ?: return@execute
                selected = b
                flyTo(b)
            } catch (e: Exception) {
                ErrorLog.record("goToName($name)", e)
            }
        }
    }

    /** Picks a random generated rocky planet around a real star and flies there. */
    fun surprise(land: Boolean) {
        bg.execute {
            try {
                val rs = world.realStars ?: return@execute
                val rng = java.util.Random()
                repeat(200) {
                    val tile = rs.base.tiles[rng.nextInt(rs.base.tiles.size)]
                    if (tile.count == 0) return@repeat
                    val star = rs.bodyFrom(tile, rng.nextInt(tile.count))
                    if (world.exoRowsFor(star) != null) return@repeat
                    val sys = world.systemFor(star)
                    val p = sys.planets.filter { if (land) it.solid else true }.randomOrNull() ?: return@repeat
                    selected = p
                    world.rememberGenerated(p)
                    flyTo(p)
                    return@execute
                }
            } catch (e: Exception) {
                ErrorLog.record("surprise", e)
            }
        }
    }

    /** Zoom presets for the slider labels: height above the ground (or distance) in metres. */
    val zoomPresets = listOf(
        "Ground" to 2.0, "Planet" to 3.0e7, "Solar system" to 1.5e13, "Nearby stars" to 2e17,
        "Milky Way" to 1.2e21, "Galaxy groups" to 3e23, "Universe" to 1.2e27,
    )

    fun zoomFractionFor(alt: Double): Float {
        val lo = kotlin.math.ln(1.7); val hi = kotlin.math.ln(camera.maxDistance())
        return ((kotlin.math.ln(alt) - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
    }

    /** Zooms out to whatever contains the current focus. */
    fun goUp() {
        val cur = selected ?: camera.target
        val p = parentOf(cur) ?: return
        selected = p
        flyTo(p)
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
