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
    private val searchExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var searchGen = 0

    /** Searches off the main thread; only the latest query's results are delivered. */
    fun searchAsync(query: String, done: (List<SearchHit>) -> Unit, finished: (() -> Unit)? = null) {
        val gen = ++searchGen
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        searchExec.execute {
            if (gen != searchGen) return@execute
            val r = try { search.search(query) } catch (e: Exception) { ErrorLog.record("search", e); emptyList() }
            if (gen != searchGen) return@execute
            main.post { if (gen == searchGen) done(r) }
            val q = query.trim()
            if (q.length < 2) { main.post { if (gen == searchGen) finished?.invoke() }; return@execute }
            // Then the slower places: every asteroid (if downloaded), then the online databases.
            extExec.execute {
                try {
                    if (gen != searchGen) return@execute
                    Thread.sleep(450) // still typing?
                    if (gen != searchGen) return@execute
                    val seen = r.map { it.id }.toHashSet()
                    val more = ArrayList<SearchHit>()
                    world.allAsteroids?.let { db ->
                        for (i in db.search(q, 8)) {
                            val b = world.asteroidBody(i) ?: continue
                            if (seen.add(b.id)) more.add(SearchHit(b.id, b.name, b.subtitle, 95))
                        }
                    }
                    world.milliquas?.let { db ->
                        for (i in db.search(q, 6)) {
                            val b = db.body(i)
                            if (seen.add(b.id)) more.add(SearchHit(b.id, b.name, "Quasar", 94))
                        }
                    }
                    if (gen != searchGen) return@execute
                    if (more.isNotEmpty()) main.post { if (gen == searchGen) done(r + more) }
                    val nq = normalize(q)
                    val exact = (r + more).any { normalize(it.name) == nq } || r.firstOrNull()?.score?.let { it >= 1000 } == true
                    if (!settings.onlineSearch || exact || q.length < 3) { main.post { if (gen == searchGen) finished?.invoke() }; return@execute }
                    onlineSearching = true
                    val net = try { Online.search(q) } finally { onlineSearching = false }
                    if (gen != searchGen) return@execute
                    val add = net.filter { seen.add(it.id) }
                    // An online object with exactly the name you typed beats a near-miss from the app.
                    val all = r + more + add
                    val sorted = all.filter { normalize(it.name) == nq || normalize(it.name.substringBefore(" (")) == nq } +
                        all.filterNot { normalize(it.name) == nq || normalize(it.name.substringBefore(" (")) == nq }
                    main.post { if (gen == searchGen) { onlineDone = gen; done(sorted); finished?.invoke() } }
                } catch (e: Exception) {
                    onlineSearching = false
                    ErrorLog.record("online search", e)
                    main.post { if (gen == searchGen) finished?.invoke() }
                }
            }
        }
    }

    private val extExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** True while an online lookup is running (the search screen shows it). */
    @Volatile var onlineSearching = false
    @Volatile var onlineDone = 0

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
        Online.init(context)
        world.start()
        // Fetch the big catalogues in the background once the app has settled.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ packs.autoInstall() }, 8000)
        val home = world.homeBody()
        // Open on the sunlit side (it opened on whatever side was night at the time).
        camera.jumpTo(home, home.position(jd), home.viewDistance(), arrivalView(home))
    }

    fun nowSeconds() = SystemClock.elapsedRealtimeNanos() / 1e9

    fun update(dt: Double, now: Double) {
        if (!paused) jd += dt * timeRate / 86400.0
        com.emotionalleaves.universe.core.Prof.time("e.camera") { synchronized(camera) {
            val before = camera.snapshot()
            freeFlightStep(dt)
            surfaceFollow()
            camera.update(dt, now) { it.position(jd) }
            // Never let the camera end up inside a planet: over the body you orbit, keep above its
            // surface; for any other body, undo the move.
            if (!camera.isFlying) {
                val cam = camera.position()
                for (b in world.solidBodiesNear(cam, jd)) {
                    val c = b.position(jd)
                    if ((cam - c).length() >= b.radius * 1.01) continue
                    if (b == camera.target) camera.setDistance(camera.minDistance()) else camera.restore(before)
                    break
                }
            }
        } }
        world.update(this, dt)
    }

    fun onUserInteraction() {}

    private var lastSpinBody: String? = null
    private var lastSpin: com.emotionalleaves.universe.core.Quatd? = null

    /** Keeps the camera turning with a nearby planet and slows rotation close to it. Holds the camera lock. */
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
            val alt = camera.altitude().coerceAtLeast(0.1)
            camera.rotateScale = (alt / t.radius * 1.6).coerceIn(0.02, 1.0)
        } else {
            camera.rotateScale = 1.0
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
        when {
            // Double tap, or tapping what is already selected: go there.
            body != null && (double || body == selected) -> { selected = body; flyTo(body) }
            // A tap that hits nothing leaves the selection alone: in a universe this big most misses
            // are near-misses, and losing the card each time was the annoying part. The card's ✕ closes it.
            body != null -> selected = body
        }
    }

    /** Other things that were under the last tap (crowded spots): offered on the card. */
    @Volatile var tapChoices: List<Body> = emptyList()

    /** Selects something by id without flying (the card's "Also here" chips). */
    fun selectId(id: String) {
        bg.execute {
            val b = world.find(id) ?: return@execute
            selected = b
            prefetch(b)
        }
    }

    val tapReach: Float get() = 36f * density

    /** Where each name is drawn (left, top, right, bottom in pixels): tapping a name picks its object. */
    @Volatile var labelBoxes: List<Pair<FloatArray, Body>> = emptyList()

    fun pick(x: Float, y: Float): Pickable? {
        val reach = 40f * density
        val hits = ArrayList<Pair<Pickable, Double>>()
        for ((box, body) in labelBoxes) {
            if (x >= box[0] && x <= box[2] && y >= box[1] && y <= box[3]) hits.add(Pickable(body, x, y, 0f, 5f, true) to -30.0)
        }
        for (p in pickables) {
            val d = hypot((p.x - x).toDouble(), (p.y - y).toDouble())
            val edge = (d - p.pixelRadius).coerceAtLeast(0.0)
            if (edge > reach) continue
            hits.add(p to edge - p.priority * 4.0)
        }
        hits.sortBy { it.second }
        val best = hits.firstOrNull()?.first
        tapChoices = hits.drop(1).map { it.first.body }.distinctBy { it.id }.filter { it.id != best?.body?.id }.take(5)
        return best
    }

    /** Starts building what is around [b] (its planets) before you arrive, so it is there on arrival. */
    fun prefetch(b: Body) {
        if (b is StarBody) bg.execute { try { world.systemFor(b) } catch (_: Exception) {} }
    }

    fun flyTo(body: Body) {
        prefetch(body)
        if (settings.gaiaStream) try { world.gaia.prepare(body.position(jd)) } catch (_: Exception) {}
        // In free flight every trip stays a free trip: travel there without snapping into orbit.
        if (camera.freeFlight) { freeTravelTo(body); return }
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


    /** The thing that contains [b]: moon → planet → star → galaxy → universe. */
    fun parentOf(b: Body?): Body? = when (b) {
        null -> null
        is SolarBody -> b.parent ?: world.milkyWay
        is PlanetBody -> b.host ?: b.star
        is StarBody -> if (b.galaxyName == "Milky Way") world.milkyWay else world.galaxyContaining(b.pos) ?: world.universe
        is DsoBody -> world.milkyWay
        is GalaxyBody -> world.universe
        is UniverseBody -> null
        is SmallBody -> world.solar.first()
        is BeltBody -> b.belt.star
        is RockBody -> if (b.zone.key == "sun") world.solar.first() else (world.find(b.zone.key) ?: world.milkyWay)
        is RemnantBody -> world.galaxyContaining(b.pos) ?: world.milkyWay
        is ExoticBody -> if (b.kind == Kind.PULSAR) world.milkyWay else world.universe
        else -> world.milkyWay
    }

    /** Last object the camera orbited, so you can get back after drifting into empty space. */
    @Volatile var lastTarget: Body? = null

    /** Re-centre on the selected object, or the last thing you were orbiting. */
    fun recenter() {
        val b = lastTarget ?: selected ?: world.homeBody()
        selected = b
        flyTo(b)
    }

    /** Short message while a background lookup runs ("Finding Mars…"); shown in the loading chip. */
    @Volatile var busy = ""

    /** Flies to an object by name (Explore lists). Builds the search index if needed. */
    fun goToName(name: String) {
        busy = "Finding $name…"
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
            } finally {
                busy = ""
            }
        }
    }

    /** Picks a random generated rocky planet around a real star and flies there. */
    fun surprise(rocky: Boolean) {
        busy = "Picking a world…"
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
                    val p = sys.planets.filter { if (rocky) it.solid else true }.randomOrNull() ?: return@repeat
                    selected = p
                    world.rememberGenerated(p)
                    flyTo(p)
                    return@execute
                }
            } catch (e: Exception) {
                ErrorLog.record("surprise", e)
            } finally {
                busy = ""
            }
        }
    }

    /** Flies to a random far-away galaxy from the cosmic web. */
    fun surpriseGalaxy() {
        busy = "Picking a galaxy…"
        bg.execute {
            try {
                val web = world.cosmicWeb ?: return@execute
                val g = world.find("gal:web${java.util.Random().nextInt(web.count)}") ?: return@execute
                selected = g
                world.rememberGenerated(g)
                flyTo(g)
            } catch (e: Exception) {
                ErrorLog.record("surpriseGalaxy", e)
            } finally {
                busy = ""
            }
        }
    }

    /** Flies to a generated black hole, neutron star or rogue planet somewhere in the Milky Way's disc. */
    fun surpriseRemnant(kind: Kind) {
        busy = "Searching the galaxy…"
        bg.execute {
            try {
                val mw = world.milkyWay
                val rng = java.util.Random()
                repeat(40) {
                    val r = 5000.0 + rng.nextDouble() * 6000.0
                    val a = rng.nextDouble() * 2 * Math.PI
                    val local = Vec3d(r * kotlin.math.cos(a), r * kotlin.math.sin(a), rng.nextGaussian() * 80.0)
                    val b = Remnants.generate(mw, local).bodies.filter { it.kind == kind }.randomOrNull() ?: return@repeat
                    selected = b
                    world.rememberGenerated(b)
                    flyTo(b)
                    return@execute
                }
            } catch (e: Exception) {
                ErrorLog.record("surpriseRemnant", e)
            } finally {
                busy = ""
            }
        }
    }

    /** Turns on the speculative region beyond the observable universe and flies out to see it. */
    fun goBeyond() {
        settings.showBeyond = true
        world.refreshBeyond()
        selected = world.universe
        synchronized(camera) {
            camera.flyTo(world.universe, UPos.ZERO, Units.UNIVERSE_RADIUS * 7, nowSeconds())
        }
    }

    // ---------------------------------------------------------------- free flight

    /** -1 back, 0 coast, +1 forward (held buttons). */
    @Volatile var thrust = 0
    /** Speed multiplier steps (×1, ×10, ×100, ×1000). Bigger jumps flung you out of the universe in a second. */
    @Volatile var warp = 0
    val warpFactors = doubleArrayOf(1.0, 10.0, 100.0, 1000.0)
    /** Seconds Forward/Back has been held: holding longer speeds you up gently (up to ×10). */
    private var held = 0.0
    private var cruise = 0.0

    val freeFlight: Boolean get() = camera.freeFlight

    fun setFreeFlight(on: Boolean) {
        synchronized(camera) { if (on) camera.enterFreeFlight() else camera.leaveFreeFlight() }
        speedRef = world.nearestDist.coerceAtLeast(1.0)
        if (!on) { thrust = 0; warp = 0; autopilot = null }
    }

    /**
     * Distance that sets the speed: drops at once when something comes close, but grows at most
     * ~2.2× per second. Speed tied straight to "distance to the nearest thing" ran away
     * exponentially at warp (out of the universe in seconds).
     */
    private var speedRef = 1e7

    /** Room to fly: distance to the nearest thing, keeping out of a low orbit around worlds. */
    private fun clearance(): Double {
        val b = world.nearestBody
        val margin = if (b != null && camera.minDistanceFor(b) > b.radius) camera.minDistanceFor(b) - b.radius else 0.0
        return (world.nearestDist - margin).coerceAtLeast(1.0)
    }

    /** Current free-flight speed (m/s): about the distance to the nearest thing per 1.5 s, times warp. */
    fun flightSpeed(): Double = speedRef / 1.5 * warpFactors[warp] * holdBoost()

    private fun holdBoost(): Double = (1.0 + 0.6 * held * held).coerceAtMost(10.0)

    /** Pinch in free flight: a step forward or back scaled to what is nearby. */
    fun freeZoom(factor: Float) {
        val room = clearance()
        val step = (room * 0.6 * kotlin.math.ln(factor.toDouble()) * 2.2 * warpFactors[warp]).coerceIn(-room * 50, room * 0.8)
        camera.moveForward(step)
        keepInsideUniverse()
    }

    // ---------------------------------------------------------------- free-flight autopilot

    /** Where free flight is heading on its own (tap something, Fly to, or the nearest-object button). */
    @Volatile var autopilot: Body? = null
        private set

    /** Travel to [b] in free flight: turn to face it and glide in, stopping at a good viewing distance. */
    fun freeTravelTo(b: Body) {
        if (!camera.freeFlight) setFreeFlight(true)
        selected = b
        prefetch(b)
        thrust = 0
        camera.userLooked = false
        autopilot = b
    }

    /** The nearest thing you are not already at: the free-flight "Nearest" button. */
    fun nearestToVisit(): Body? {
        val cam = camera.position()
        return world.nearestFew.firstOrNull { (b, _) ->
            (b.position(jd) - cam).length() > arrivalDistance(b) * 1.3
        }?.first ?: world.nearestFew.firstOrNull()?.first
    }

    fun travelToNearest() { nearestToVisit()?.let { freeTravelTo(it) } }

    private fun arrivalDistance(b: Body): Double =
        kotlin.math.max(b.viewDistance(), camera.minDistanceFor(b)).let { if (it > 0) it else 1e3 }

    /** One step of the autopilot; false when there is none (or it just finished or was cancelled). */
    private fun autopilotStep(dt: Double): Boolean {
        val b = autopilot ?: return false
        if (thrust != 0 || camera.userLooked) { autopilot = null; camera.userLooked = false; return false }
        val centre = b.position(jd)
        val cam = camera.position()
        val rel = centre - cam
        val dist = rel.length()
        val stop = arrivalDistance(b)
        if (dist <= 0) { autopilot = null; return false }
        val dir = rel / dist
        camera.turnToward(dir, kotlin.math.min(1.0, dt * 3.0))
        // Where to end up: on the side you came from, swung towards the day side for planets and
        // moons (arriving over the night side showed a black disc).
        val light: UPos? = when (b) {
            is SolarBody -> if (b.kind == Kind.STAR) null else UPos.ZERO
            is PlanetBody -> b.star.pos
            else -> null
        }
        var side = -dir
        if (light != null) {
            val toLight = (light - centre).let { if (it.lengthSq() > 0) it.normalized() else side }
            side = (side + toLight * 2.5).let { if (it.lengthSq() < 1e-9) toLight else it.normalized() }
        }
        val goal = centre + side * stop
        val gap = goal - cam
        val remain = gap.length()
        if (remain <= stop * 0.02 + 1.0) { autopilot = null; cruise = 0.0; return false }
        // Close a share of the remaining gap every second, a bigger share the more orders of magnitude
        // are left: about 3–8 s whether it is a moon next door or a galaxy billions of light years away.
        val k = 2.0 + 0.25 * kotlin.math.ln(kotlin.math.max(remain / stop, 1.0))
        camera.shift(gap * (1 - kotlin.math.exp(-dt * k)))
        keepInsideUniverse()
        return true
    }

    private fun freeFlightStep(dt: Double) {
        if (!camera.freeFlight) { autopilot = null; return }
        if (autopilotStep(dt)) return
        held = if (thrust != 0) held + dt else 0.0
        val room = clearance()
        val ref = world.nearestDist.coerceAtLeast(1.0)
        speedRef = if (ref < speedRef) ref else speedRef * kotlin.math.min(ref / speedRef, kotlin.math.exp(dt * 1.5))
        val targetV = thrust * flightSpeed()
        // Ease in and out so starting and stopping feel smooth.
        cruise += (targetV - cruise) * kotlin.math.min(1.0, dt * 4.0)
        if (kotlin.math.abs(cruise) > 1e-9) {
            // Never fly into (or through) something: when heading towards the nearest thing, no step
            // longer than the room left; heading away, no limit.
            val b = world.nearestBody
            val dirMove = camera.forward() * kotlin.math.sign(cruise)
            val towards = if (b != null) {
                val to = b.position(jd) - camera.position()
                val l = to.length()
                if (l > 0) dirMove.dot(to / l) else 1.0
            } else 1.0
            val limit = if (towards > 0.05) room * 0.8 / towards else Double.MAX_VALUE
            val step = (cruise * dt).coerceIn(-limit, limit)
            camera.moveForward(step)
        }
        keepInsideUniverse()
    }

    /** The modelled universe has an edge (9,000× the observable radius, or 3× without "beyond"): stop there. */
    fun keepInsideUniverse() {
        val maxR = camera.maxDistance() * 0.98
        val p = camera.position() - UPos.ZERO
        val r = p.length()
        if (r > maxR) {
            camera.strafe(0.0, 0.0)
            camera.moveForward(0.0)
            val back = p * (maxR / r - 1.0)
            camera.shift(back)
            cruise = 0.0
        }
    }

    /** Zoom presets for the slider labels: height above the ground (or distance) in metres. */
    val zoomPresets = listOf(
        "Planet" to 3.0e7, "Solar system" to 1.5e13, "Nearby stars" to 2e17,
        "Milky Way" to 1.2e21, "Galaxy groups" to 3e23, "Universe" to 2.0e27, "Beyond" to 2e29,
    )

    /** Lowest height the slider reaches: a low orbit over a world, or close to anything else. */
    fun minZoomAltitude(): Double {
        val t = camera.zoomTarget()
        return (camera.minDistanceFor(t) - camera.surfaceOf(t)).coerceAtLeast(1.7)
    }

    fun zoomFractionFor(alt: Double): Float {
        val lo = kotlin.math.ln(minZoomAltitude()); val hi = kotlin.math.ln(camera.maxDistance())
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

    /** A structure shown on its own: only what lies inside the sphere is drawn, labelled and tappable. */
    class Region(val body: Body, val center: UPos, val radius: Double)

    @Volatile var isolated: Region? = null
        private set

    /** The size of the region to show for [b] on its own, or null if it isn't a place with things inside. */
    fun regionRadius(b: Body): Double? = when (b.kind) {
        Kind.SUPERCLUSTER, Kind.VOID -> b.radius
        Kind.GALAXY_CLUSTER -> if (b is FamousBody) b.radius else b.radius * 2.0
        Kind.GALAXY -> b.radius * 1.6
        Kind.CLUSTER, Kind.NEBULA -> b.radius * 1.5
        else -> null
    }

    fun isolateSelected() { selected?.let { isolate(it) } }

    /** Shows only [b] and what is inside it (and flies out to see it whole); null shows everything again. */
    fun isolate(b: Body?) {
        val r = b?.let { regionRadius(it) }
        if (b == null || r == null) { isolated = null; return }
        isolated = Region(b, b.position(jd), r)
        selected = b
        if (camera.freeFlight) setFreeFlight(false)
        flyTo(b)
    }

    /** Zoom slider on a log scale of height above the ground: 0 = standing, 1 = whole universe. */
    fun zoomFraction(): Float = synchronized(camera) {
        val lo = ln(minZoomAltitude()); val hi = ln(camera.maxDistance())
        ((ln(camera.altitude().coerceAtLeast(1.7)) - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
    }

    fun setZoomFraction(f: Float, smooth: Boolean = false) {
        synchronized(camera) {
            val lo = ln(minZoomAltitude()); val hi = ln(camera.maxDistance())
            retargetIfAdrift()
            val d = camera.surfaceOf(camera.zoomTarget()) + exp(lo + (hi - lo) * f.coerceIn(0f, 1f))
            if (smooth) camera.glideTo(d) else camera.setDistance(d)
        }
    }

    /**
     * When you have drifted into empty space but something is selected, zooming should act on
     * that object: turn to face it (a short flight at the current distance), then zoom towards it.
     * Call while holding the camera lock.
     */
    fun retargetIfAdrift() {
        if (camera.target != null || camera.isFlying) return
        val sel = selected ?: return
        val p = sel.position(jd)
        val d = (camera.position() - p).length()
        if (d <= 0) return
        camera.flyTo(sel, p, d, nowSeconds(), null)
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
