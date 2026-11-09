package com.emotionalleaves.universe.engine

import kotlin.math.pow
import kotlin.math.max
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d

/** Everything that exists: catalogs, generated content and the scale-test objects. */
class World(private val engine: Engine) {

    /** The Sun first, then planets and moons. */
    /** Sun, planets, the hand-made major moons and every catalogued moon (planets/moons.tsv). */
    val solar: List<SolarBody> = SolarSystem.build().toMutableList().also {
        try { CatalogMoons.addTo(engine.context, it) } catch (e: Throwable) { ErrorLog.record("loading moons", e) }
    }

    /** Real asteroids and comets; null until loaded or if the data is missing. */
    @Volatile var smallBodies: SmallBodies? = null
        private set
    /** Every known asteroid (Minor Planet Center list), once downloaded; null otherwise. */
    @Volatile var allAsteroids: AsteroidDb? = null
        private set

    /** Opens (or drops) the full asteroid list after it was downloaded or deleted. */
    fun reloadAsteroids() {
        loader3.execute {
            allAsteroids = if (AsteroidDb.installed(engine.context.filesDir)) AsteroidDb.open(engine.context.filesDir) else null
            searchStale = true
            loaded("all asteroids (${allAsteroids?.count ?: 0})")
        }
    }

    private val asteroidBodies = object : LinkedHashMap<Int, Body>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Body>?) = size > 48
    }

    /** Asteroid [i] of the full list (the built-in entry, with its measured size, when there is one). */
    fun asteroidBody(i: Int): Body? = synchronized(asteroidBodies) {
        asteroidBodies[i] ?: run {
            val db = allAsteroids ?: return null
            if (i !in 0 until db.count) return null
            val b = db.body(i)
            val base = smallBodies?.indexOf(b.name)?.let { smallBodies?.body(it) }
            (base ?: b).also { asteroidBodies[i] = it }
        }
    }

    /** Pulsars, quasars and galaxy clusters. */
    @Volatile var exotic: ExoticCatalog = ExoticCatalog(emptyList(), emptyList(), emptyList())
        private set
    val earth: SolarBody = solar.first { it.id == "sol:earth" }

    // ---- Scale-test scene (Round 2): a 1 m cube on an Earth-sized sphere, and a marker 10 Gly away.
    // Lives far from the solar system so it never overlaps a real planet. Enable it in Settings.
    private val testOrigin = UPos.ZERO + Vec3d.fromRaDec(200.0, 60.0) * (3000 * Units.AU)
    val testPlanet = TestPlanet(testOrigin)
    private val cubeDir = Vec3d.fromRaDec(15.0, 35.0)
    val testCube = MarkerBody(
        "test:cube", "1 m cube", testOrigin + cubeDir * (Units.R_EARTH + 0.5), 0.5, "Scale test: 1 metre across",
    )
    val farMarker = MarkerBody(
        "test:far", "10 billion light years", UPos.ZERO + Vec3d.fromRaDec(83.8, -5.4) * (1e10 * Units.LY), 0.0,
        "Scale test marker",
    )
    val cubeOrientationUp: Vec3d get() = cubeDir

    val milkyWay = GalaxyBody.milkyWay()
    /** Galaxies with positions (the Milky Way first; catalogue galaxies once loaded). */
    @Volatile var galaxies: List<GalaxyBody> = listOf(milkyWay)
    private val galaxyIndex = java.util.concurrent.ConcurrentHashMap<String, GalaxyBody>().also { it[milkyWay.id] = milkyWay }

    // ---------------------------------------------------------------- nearest thing (free-flight speed)

    /** Distance (m) to the surface of the nearest thing around the camera, and what it is. */
    @Volatile var nearestDist = 1e7
        private set
    @Volatile var nearestBody: Body? = null
        private set
    private var nearestTimer = 1.0

    private fun updateNearest(cam: UPos, dt: Double) {
        nearestTimer += dt
        if (nearestTimer < 0.25) return
        nearestTimer = 0.0
        var best = Double.MAX_VALUE
        var bestBody: Body? = null
        // A few nearest things, for "go to the nearest" in free flight (field galaxies by id, built only if kept).
        val top = ArrayList<Pair<Any, Double>>(6)
        fun keep(b: Any, d: Double) {
            if (top.size >= 5 && d >= top.last().second) return
            top.add(b to d); top.sortBy { it.second }
            if (top.size > 5) top.removeAt(top.size - 1)
        }
        fun consider(b: Body, d: Double) { if (d < best) { best = d; bestBody = b }; keep(b, d) }
        val jd = engine.jd
        for (b in solar) consider(b, (b.position(jd) - cam).length() - b.radius)
        for (b in Famous.all) if (b.kind != Kind.VOID && b.kind != Kind.SUPERCLUSTER) consider(b, (b.position(jd) - cam).length() - b.radius)
        activeSystem?.let { s ->
            consider(s.star, (s.star.pos - cam).length() - s.star.radius)
            for (p in s.planets) { consider(p, (p.position(jd) - cam).length() - p.radius); for (m in p.moons) consider(m, (m.position(jd) - cam).length() - m.radius) }
        }
        realStars?.let { rs ->
            rs.nearest(cam - UPos.ZERO, 20.0)?.let { (t, i) -> val s = rs.bodyFrom(t, i); consider(s, (s.pos - cam).length() - s.radius) }
        }
        nearestGenerated(cam, 50.0)?.let { consider(it, (it.pos - cam).length() - it.radius) }
        rocks?.bodies?.let { for (r in it) consider(r, (r.pos - cam).length() - r.radius) }
        remnants?.bodies?.let { for (r in it) consider(r, (r.pos - cam).length() - r.radius) }
        // Galaxies: only count them from outside (inside one, its stars are what is near).
        for (g in galaxies) {
            val d = (g.center - cam).length() - g.radius
            if (d > 0) consider(g, d)
        }
        field?.let { f -> for (i in 0 until f.count) { val d = (f.position(i) - cam).length() - f.radiusPc[i] * Units.PC; if (d > 0) { if (d < best) { best = d; bestBody = null }; keep(f.id(i), d) } } }
        // Galaxies are never more than a few tens of Mpc apart, so never assume more empty space than
        // that (at high warp the galaxy field cannot keep up, which would make speed run away).
        nearestDist = best.coerceIn(1.0, 30 * Units.MPC)
        nearestBody = bestBody ?: (top.firstOrNull()?.first as? String)?.let { GalaxyField.fromId(it) }
        nearestFew = top.mapNotNull { (b, d) -> ((b as? Body) ?: (b as? String)?.let { GalaxyField.fromId(it) })?.let { it to d } }
    }

    /** The few nearest things to the camera (with distance to their surface), nearest first. */
    @Volatile var nearestFew: List<Pair<Body, Double>> = emptyList()
        private set

    /** Rocks, boulders and comet nuclei generated around the camera in belts and clouds. */
    @Volatile var rocks: RockField.Generated? = null
    private val rockExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var rockBusy = false
    private var rockAt: UPos? = null
    private var rockZonesKey: String? = null
    private var rockTimer = 0.0
    private val zoneCache = java.util.concurrent.ConcurrentHashMap<String, List<RockField.Zone>>()

    fun zonesForKey(key: String): List<RockField.Zone>? {
        if (key == "sun") return RockField.solarZones
        zoneCache[key]?.let { return it }
        val star = find(key) as? StarBody ?: return null
        return RockField.zonesFor(systemFor(star)).also { zoneCache[key] = it }
    }

    private fun updateRocks(cam: UPos, dt: Double) {
        rockTimer += dt
        if (rockTimer < 0.25 || rockBusy) return
        rockTimer = 0.0
        if (!engine.settings.showGenerated) { rocks = null; rockAt = null; return }
        val sysKey = activeSystem?.star?.id
        val zones = RockField.solarZones + (sysKey?.let { zonesForKey(it) } ?: emptyList())
        val key = sysKey ?: "sun"
        val last = rockAt
        if (last != null && key == rockZonesKey) {
            val moved = (cam - last).length() / Units.AU
            val finest = RockField.finestCellAu(zones, cam)
            // Outside every belt nothing is generated, so checking again is cheap.
            if (finest != Double.MAX_VALUE && moved < 0.4 * finest) return
            if (finest == Double.MAX_VALUE && rocks?.bodies.isNullOrEmpty() && moved < 0.01) return
        }
        rockAt = cam
        rockZonesKey = key
        rockBusy = true
        rockExec.execute {
            try { rocks = RockField.generate(zones, cam) } catch (e: Throwable) { ErrorLog.record("generating rocks", e) } finally { rockBusy = false }
        }
    }

    /** Black holes, neutron stars and rogue planets generated around the camera. */
    @Volatile var remnants: Remnants.Generated? = null

    /** Stars generated around the camera (rule 2: never where real ones are). */
    @Volatile var generated: GeneratedStars? = null
        private set
    private val genExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var genBusy = false
    private var genLocal: Vec3d? = null
    private var genGalaxy: String? = null
    private var genTimer = 0.0

    fun galaxyById(id: String): GalaxyBody? = galaxyIndex[id] ?: (if (id.startsWith("gal:")) find(id) as? GalaxyBody else null)

    fun isRegistered(id: String) = galaxyIndex.containsKey(id)

    /** Galaxies generated around the camera beyond the catalogues (see [GalaxyField]). */
    @Volatile var field: FieldGalaxies? = null
        private set
    private var fieldCell: Triple<Int, Int, Int>? = null
    @Volatile private var fieldBusy = false
    private var fieldTimer = 0.0

    private fun updateField(cam: UPos) {
        if (!engine.settings.showGenerated) { field = null; fieldCell = null; return }
        if (fieldBusy) return
        val c = (cam - UPos.ZERO) / Units.MPC
        val key = Triple(kotlin.math.floor(c.x).toInt(), kotlin.math.floor(c.y).toInt(), kotlin.math.floor(c.z).toInt())
        if (key == fieldCell) return
        fieldCell = key
        fieldBusy = true
        fieldExec.execute {
            try { field = GalaxyField.generate(cam) } catch (e: Throwable) { ErrorLog.record("generating galaxies", e) } finally { fieldBusy = false }
        }
    }

    /** Field galaxies close enough to show a shape are added to the galaxy list (drawn and enterable). */
    private fun registerNearField(cam: UPos) {
        val f = field ?: return
        val add = ArrayList<GalaxyBody>()
        for (i in 0 until f.count) {
            val d = (f.position(i) - cam).length()
            if (d > f.radiusPc[i] * Units.PC * 300) continue
            val id = f.id(i)
            if (galaxyIndex.containsKey(id)) continue
            GalaxyField.fromId(id)?.let { add.add(it) }
            if (add.size >= 16) break
        }
        if (add.isEmpty()) return
        synchronized(galaxyLock) {
            val fresh = add.filter { !galaxyIndex.containsKey(it.id) }
            for (g in fresh) galaxyIndex[g.id] = g
            if (fresh.isNotEmpty()) galaxies = galaxies + fresh
        }
    }

    val universe = UniverseBody()

    /** Generated large-scale structure beyond the catalogues. */
    @Volatile var cosmicWeb: CosmicWebData? = null
    /** Speculative galaxies beyond the observable universe (only when that setting is on). */
    @Volatile var outerWeb: CosmicWebData? = null

    /** Builds or drops the "beyond the observable universe" region to match the setting. */
    fun refreshBeyond() {
        val on = engine.settings.showBeyond && engine.settings.showGenerated
        synchronized(engine.camera) { engine.camera.maxFactor = if (on) CosmicWeb.BEYOND_FACTOR else 3.0 }
        if (!on) { outerWeb = null; return }
        if (outerWeb != null) return
        webExec.execute {
            try {
                val o = outerWeb ?: CosmicWebData(CosmicWeb.EDGE_MPC, cachedFloats("outer_v${CosmicWeb.VERSION}") { CosmicWeb.generateOuter().data }, "out")
                outerWeb = if (engine.settings.showBeyond && engine.settings.showGenerated) o else null
            } catch (e: Throwable) { ErrorLog.record("beyond", e) }
        }
    }

    /** Glow of the unresolved large-scale structure (see [CosmicHaze]); Mpc, 7 floats per blob. */
    @Volatile var haze: FloatArray? = null
        private set
    /** Optional GLADE+ pack (millions of real galaxies). */
    @Volatile var glade: GladePack? = null
        private set

    /** Reloads everything that depends on installed packs (call from any thread). */
    fun packsChanged() {
        loader.execute {
            val rs = realStars
            if (rs != null) {
                // AT-HYG (750,000 real stars to magnitude 11, from Tycho-2 and Gaia) ships inside the app.
                rs.packs = try {
                    listOfNotNull(StarFiles.open(engine.context, "stars/athyg.bin")?.use { StarFiles.read(it, "AT-HYG") })
                } catch (_: Throwable) { emptyList() }
                loaded("AT-HYG (${rs.packs.sumOf { it.count }})")
                // Real Gaia stars within 330 light years, also shipped with the app (deduplicated against the above).
                try { rs.gaia = gaia; gaia.loadNear(engine.context, rs); loaded("gaia near (${gaia.near?.count ?: 0})") } catch (e: Throwable) { ErrorLog.record("loading Gaia stars", e) }
                // A deeper real catalogue means generated stars must stay fainter (rule 2).
                StarField.catalogLimit = if (rs.packs.isNotEmpty()) 10.5 else 8.0
                genLocal = null
            }
        }
        reloadLargeScale()
    }

    /** Adds a generated galaxy (from the cosmic web) so it can be entered like a real one. */
    fun registerExtraGalaxy(g: GalaxyBody) {
        synchronized(galaxyLock) {
            if (galaxyIndex.containsKey(g.id)) return
            galaxyIndex[g.id] = g
            galaxies = galaxies + g
        }
    }

    /** Guards changes to [galaxies]: several threads (taps, lookups, loading) add to it. */
    private val galaxyLock = Any()

    /** (Re)loads the GLADE+ pack and regenerates the cosmic web beyond whatever real data we have. */
    fun reloadLargeScale() {
        webExec.execute {
            val maxPts = when (engine.settings.quality) { 0 -> 400_000; 2 -> 3_000_000; else -> 1_200_000 }
            glade = GladePack.load(engine.context, maxPts)
            // With millions of real galaxies installed, generated ones must be fainter (rule 2).
            GalaxyField.catalogLimit = if (glade != null) 17.0 else 14.0
            fieldCell = null
            val inner = glade?.let { maxOf(it.maxMpc, 300.0) } ?: 300.0
            if (cosmicWeb?.innerMpc != inner) {
                val key = "v${CosmicWeb.VERSION}_${inner.toLong()}"
                cosmicWeb = CosmicWebData(inner, cachedFloats("web_$key") { CosmicWeb.generate(inner).data })
                haze = try { cachedFloats("haze_$key") { CosmicHaze.generate(inner) } } catch (e: Throwable) { ErrorLog.record("haze", e); null }
                loaded("cosmic web")
            }
        }
    }

    fun registerGalaxies(list: List<GalaxyBody>) {
        synchronized(galaxyLock) {
            for (g in list) galaxyIndex[g.id] = g
            // Keep anything registered meanwhile (taps on distant galaxies during loading).
            val ids = list.mapTo(HashSet()) { it.id }
            galaxies = listOf(milkyWay) + list + galaxies.filter { !it.isMilkyWay && it.id !in ids }
        }
    }

    /** The galaxy the camera is inside, if any. */
    fun galaxyContaining(cam: UPos): GalaxyBody? {
        var best: GalaxyBody? = null
        var bestF = 1.5
        for (g in nearGalaxies) {
            val f = (g.center - cam).length() / g.radius
            if (f < bestF) { bestF = f; best = g }
        }
        return best
    }

    /** Up to 4 galaxies within 40 of their own radii of the camera, nearest (relative to size) first. */
    @Volatile var nearGalaxies: List<GalaxyBody> = listOf(milkyWay)
        private set
    private var nearTimer = 1.0

    private fun updateNearGalaxies(cam: UPos, dt: Double) {
        nearTimer += dt
        if (nearTimer < 0.3) return
        nearTimer = 0.0
        updateField(cam)
        fieldTimer += 0.3
        if (fieldTimer >= 1.5) { fieldTimer = 0.0; registerNearField(cam) }
        val list = galaxies
        val cand = ArrayList<Pair<GalaxyBody, Double>>()
        for (g in list) {
            val f = (g.center - cam).length() / g.radius
            if (f < 40) cand.add(g to f)
        }
        cand.sortBy { it.second }
        nearGalaxies = cand.take(4).map { it.first }
    }

    private fun updateGenerated(cam: UPos, dt: Double) {
        genTimer += dt
        if (genTimer < 0.3 || genBusy) return
        genTimer = 0.0
        if (!engine.settings.showGenerated) { generated = null; remnants = null; genGalaxy = null; return }
        val g = galaxyContaining(cam)
        if (g == null) { generated = null; remnants = null; genGalaxy = null; return }
        val local = g.toLocal(cam)
        val last = genLocal
        if (genGalaxy == g.id && last != null && (local - last).length() < 4.0) return
        genBusy = true
        genGalaxy = g.id
        genLocal = local
        genExec.execute {
            try {
                generated = StarField.generate(g, local, supergiants = engine.settings.quality > 0)
                remnants = Remnants.generate(g, local)
            } catch (e: Throwable) {
                ErrorLog.record("generating stars", e)
            } finally {
                genBusy = false
            }
        }
    }

    /**
     * The star you are pointing at when you tap the glow of a galaxy: walks along the tap ray,
     * finds where the galaxy's light comes from, and generates the stars there. Null when the ray
     * only crosses faint sky (so tapping dark sky still clears the selection).
     */
    fun starAlongRay(cam: UPos, dir: Vec3d): StarBody? {
        if (!engine.settings.showGenerated) return null
        for (g in nearGalaxies.sortedBy { (it.center - cam).length() }) {
            val o = g.toLocal(cam)
            val d = g.frame.conjugate().rotate(dir).normalized()
            val r = g.radiusPc * 1.3
            val b = o.dot(d)
            val disc = b * b - (o.dot(o) - r * r)
            if (disc <= 0) continue
            val tEnd = -b + kotlin.math.sqrt(disc)
            val tStart = max(-b - kotlin.math.sqrt(disc), 40.0)
            if (tEnd <= tStart) continue
            val n = 120
            val ratio = tEnd / tStart
            var column = 0.0
            val scores = ArrayList<Pair<Double, Double>>(n)
            var prevT = tStart
            for (k in 0 until n) {
                val t = tStart * ratio.pow((k + 1.0) / n)
                val mid = 0.5 * (t + prevT)
                val p = o + d * mid
                val rho = g.model.density(p.x, p.y, p.z)
                column += rho * (t - prevT)
                scores.add(mid to rho * (t - prevT))
                prevT = t
            }
            // Only where the galaxy glows visibly (stars per square parsec along the line).
            // Inside a galaxy: the visible band (~200). From outside the whole disc counts.
            val inside = o.length() < g.radiusPc
            if (column < (if (inside) 200.0 else 15.0)) continue
            val sunLocal = if (g.isMilkyWay) g.toLocal(UPos.ZERO) else null
            for ((t, _) in scores.sortedByDescending { it.second }.take(6)) {
                val p = o + d * t
                val tier = StarField.tierFor(0, g.model.density(p.x, p.y, p.z))
                val size = StarField.cellSize(tier)
                val cx = kotlin.math.floor(p.x / size).toInt()
                val cy = kotlin.math.floor(p.y / size).toInt()
                val cz = kotlin.math.floor(p.z / size).toInt()
                var best: StarBody? = null
                var bestOff = Double.MAX_VALUE
                StarField.generateCell(g, tier, cx, cy, cz, sunLocal) { local, m, temp, i ->
                    val rel = local - o
                    val along = rel.dot(d)
                    if (along <= 0) return@generateCell
                    val off = (rel - d * along).length() / along   // angle from the tap ray
                    if (off < bestOff) {
                        bestOff = off
                        best = StarField.makeBody(g, g.toWorld(local), m, temp, StarField.cellKey(cx, cy, cz), tier, i)
                    }
                }
                if (best != null) return best
            }
        }
        return null
    }

    /** Nearest generated star to [cam] within [maxPc]. */
    fun nearestGenerated(cam: UPos, maxPc: Double): StarBody? {
        val gs = generated ?: return null
        val rel = (cam - gs.anchor) / Units.PC
        var best = -1
        var bestD = maxPc * maxPc
        val d = gs.data
        for (i in 0 until gs.count) {
            val dx = d[i * 7] - rel.x; val dy = d[i * 7 + 1] - rel.y; val dz = d[i * 7 + 2] - rel.z
            val q = dx * dx + dy * dy + dz * dz
            if (q < bestD) { bestD = q; best = i }
        }
        return if (best >= 0) gs.body(best) else null
    }

    /** Set when catalogues change so the search index is rebuilt. */
    @Volatile var searchStale = false

    private val byId = HashMap<String, Body>()
    private val byIdConcurrent = java.util.concurrent.ConcurrentHashMap<String, Body>()
    private val loader = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** Startup loads run side by side (stars, galaxies, small bodies, cosmic web) instead of one after another. */
    private val loader2 = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** What you see first (stars, galaxies) gets the CPU first on phones with few cores. */
    private val background = java.util.concurrent.ThreadFactory { r -> Thread(r).apply { priority = Thread.MIN_PRIORITY + 1; isDaemon = true } }
    private val loader3 = java.util.concurrent.Executors.newSingleThreadExecutor(background)
    private val webExec = java.util.concurrent.Executors.newSingleThreadExecutor(background)
    private val fieldExec = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * Generated point clouds are the same on every launch, so they are kept on disk after the first
     * build: reading 6 MB is much faster than regenerating it.
     */
    private fun cachedFloats(name: String, build: () -> FloatArray): FloatArray {
        val f = java.io.File(engine.context.cacheDir, "gen/$name.bin")
        try {
            if (f.exists() && f.length() >= 4 && f.length() % 4 == 0L) {
                val bytes = f.readBytes()
                val fb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                return FloatArray(fb.remaining()).also { fb.get(it) }
            }
        } catch (_: Throwable) {
        }
        val data = build()
        try {
            f.parentFile?.mkdirs()
            val bb = java.nio.ByteBuffer.allocate(data.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(data)
            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(bb.array())
            tmp.renameTo(f)
        } catch (_: Throwable) {
        }
        return data
    }

    /** Real star catalogues; null until loaded (or if the data is missing). */
    @Volatile var realStars: RealStars? = null
        private set
    @Volatile var starsFailed = false
        private set
    @Volatile var exo: ExoCatalog? = null
        private set
    /** Nebulae and star clusters (OpenNGC). */
    @Volatile var dsos: List<DsoBody> = emptyList()
        private set
    private val exoForStar = java.util.concurrent.ConcurrentHashMap<String, List<ExoRow>>()

    /** The star system the camera is in (other than our own), with its planets. */
    @Volatile var activeSystem: StarSystem? = null
        private set
    private val systemCache = object : LinkedHashMap<String, StarSystem>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StarSystem>?) = size > 12
    }
    private var systemTimer = 0.0

    private val startNanos = System.nanoTime()
    /** Startup timing in the log (UniverseDiag), so slow loads on a phone can be found. */
    private fun loaded(what: String) {
        try { android.util.Log.i("UniverseDiag", "loaded $what at %.1f s".format((System.nanoTime() - startNanos) / 1e9)) } catch (_: Throwable) {}
    }

    fun start() {
        extraStarNear = { cam -> nearestGenerated(cam, 0.1) }
        for (b in solar) byId[b.id] = b
        for (b in testBodies()) byId[b.id] = b
        byId[universe.id] = universe
        byId[milkyWay.id] = milkyWay
        loader.execute {
            try {
                // Positions first (fast) so the sky appears; names and figures follow.
                realStars = RealStars.loadBase(engine.context)
                loaded("stars")
                realStars?.let { RealStars.loadNames(engine.context, it) }
                searchStale = true

            } catch (e: Throwable) {
                ErrorLog.record("loading stars", e)
            }
            if (realStars == null) starsFailed = true
            loaded("star names")
            try {
                loadExoplanets()
                searchStale = true
            } catch (e: Throwable) {
                ErrorLog.record("loading exoplanets", e)
            }
        }
        // Star packs (queued behind the stars on [loader]) and the cosmic web (its own thread).
        packsChanged()
        refreshBeyond()
        loader2.execute {
            try {
                registerGalaxies(GalaxyCatalog.load(engine.context))
                searchStale = true
                loaded("galaxies")
            } catch (e: Throwable) {
                ErrorLog.record("loading galaxies", e)
            }
            try {
                val list = DsoBody.load(engine.context)
                for (d in list) byIdConcurrent[d.id] = d
                dsos = list
                searchStale = true
            } catch (e: Throwable) {
                ErrorLog.record("loading nebulae", e)
            }
            try { Describe.load(engine.context) } catch (e: Throwable) { ErrorLog.record("loading descriptions", e) }
        }
        loader3.execute {
            try {
                exotic = ExoticCatalog.load(engine.context)
                GalaxyField.clusters = exotic.clusters
                fieldCell = null
                smallBodies = SmallBodies.load(engine.context)
                if (AsteroidDb.installed(engine.context.filesDir)) allAsteroids = AsteroidDb.open(engine.context.filesDir)
                searchStale = true
                loaded("asteroids, quasars, clusters")
            } catch (e: Throwable) {
                ErrorLog.record("loading asteroids, quasars and clusters", e)
            }
        }
    }

    /** Matches exoplanet hosts to catalogue stars; hosts not in HYG become extra stars. */
    private fun loadExoplanets() {
        val cat = ExoCatalog.load(engine.context) ?: return
        val rs = realStars
        val extraStars = ArrayList<FloatArray>()
        val extraIds = ArrayList<Int>()
        val extraInfo = ArrayList<StarInfo>()
        var k = 0
        for ((host, rows) in cat.byHost) {
            val r0 = rows[0]
            val hip = r0.hip.removePrefix("HIP ").trim()
            val hd = r0.hd.removePrefix("HD ").trim()
            val id = rs?.let { (if (hip.isNotEmpty()) it.hipToId[hip] else null) ?: (if (hd.isNotEmpty()) it.hdToId[hd] else null) }
            if (id != null) {
                exoForStar["hyg:$id"] = rows
                continue
            }
            val d = r0.distPc ?: continue
            val teff = r0.stTeff ?: 5000.0
            val rad = r0.stRad ?: 1.0
            val v = Vec3d.fromRaDec(r0.ra, r0.dec) * d
            val c = StarBody.tempColor(teff)
            val sid = 20_000_000 + k++
            extraStars.add(floatArrayOf(v.x.toFloat(), v.y.toFloat(), v.z.toFloat(), c[0], c[1], c[2],
                SystemFactory.absMagFrom(teff, rad).toFloat()))
            extraIds.add(sid)
            extraInfo.add(StarInfo(sid, host, "", "", "", "", r0.stSpect, d, "", teff, ""))
            exoForStar["hyg:$sid"] = rows
        }
        if (rs != null && extraStars.isNotEmpty()) {
            rs.addInfo(extraInfo)
            rs.exoHosts = StarCatalog.fromStars(extraStars, extraIds.toIntArray(), "Exoplanet hosts")
        }
        exo = cat
    }

    fun exoRowsFor(star: StarBody): List<ExoRow>? = exoForStar[star.id]

    /** Body id of the star hosting the named exoplanet system. */
    fun starIdForHost(host: String): String? {
        for ((sid, rows) in exoForStar) if (rows.isNotEmpty() && rows[0].host == host) return sid
        return null
    }

    // ------------------------------------------------------------------ generated objects you have seen

    private val visitedPrefs = engine.context.getSharedPreferences("visited", android.content.Context.MODE_PRIVATE)

    /** Remembers a generated object so its catalogue-style name can be searched later (rule 6). */
    fun rememberGenerated(b: Body) {
        if (b.source != Source.GENERATED) return
        val list = LinkedHashMap<String, String>()
        list[b.name] = b.id
        for ((n, id) in visitedGenerated()) if (list.size < 300 && n != b.name) list[n] = id
        val arr = org.json.JSONArray()
        for ((n, id) in list) arr.put(org.json.JSONObject().put("n", n).put("id", id))
        visitedPrefs.edit().putString("list", arr.toString()).apply()
    }

    fun visitedGenerated(): List<Pair<String, String>> = try {
        val arr = org.json.JSONArray(visitedPrefs.getString("list", "[]") ?: "[]")
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.getString("n") to o.getString("id") } }
    } catch (_: Exception) {
        emptyList()
    }

    /** "Universe › Milky Way › Sun › Earth" for where the camera is. */
    fun breadcrumb(cam: UPos, target: Body?): String {
        val parts = ArrayList<String>()
        if ((cam - UPos.ZERO).length() > Units.UNIVERSE_RADIUS) return "Beyond the observable universe"
        parts.add("Universe")
        val g = galaxyContaining(cam)
        if (g != null) parts.add(g.name) else if ((cam - UPos.ZERO).length() < 4 * Units.MLY) parts.add("Local Group")
        val sys = activeSystem
        val nearSun = (cam - UPos.ZERO).length() < 0.6 * Units.LY
        when {
            nearSun -> parts.add("Sun")
            sys != null -> parts.add(sys.star.name)
        }
        when (target) {
            is SolarBody -> if (target.parent != null) {
                if (target.kind == Kind.MOON && target.parent.parent != null) parts.add(target.parent.name)
                parts.add(target.name)
            }
            is PlanetBody -> {
                target.host?.let { parts.add(it.name.removePrefix(target.star.name).trim().ifEmpty { it.name }) }
                parts.add(target.name.removePrefix(target.star.name).trim().ifEmpty { target.name })
            }
            is DsoBody -> parts.add(target.name)
            is SmallBody, is RemnantBody, is ExoticBody -> parts.add(target.name)
            else -> {}
        }
        return parts.joinToString(" › ")
    }

    /** Forget built systems (after turning generated objects on or off). */
    fun clearSystems() {
        synchronized(systemCache) { systemCache.clear() }
        activeSystem = null
        genLocal = null
    }

    fun systemFor(star: StarBody): StarSystem = synchronized(systemCache) {
        systemCache.getOrPut(star.id) {
            val rows = exoForStar[star.id]
            when {
                rows != null -> SystemFactory.real(star, rows)
                engine.settings.showGenerated -> SystemFactory.generated(star)
                else -> StarSystem(star, emptyList(), false)
            }
        }
    }

    /** Picks the star system around the camera (call a few times a second). */
    private fun updateActiveSystem() {
        val cam: UPos
        val target: Body?
        synchronized(engine.camera) { cam = engine.camera.position(); target = engine.camera.target }
        val fromSun = (cam - UPos.ZERO).length()
        var star: StarBody? = when (target) {
            is StarBody -> target.takeIf { (it.pos - cam).length() < 0.6 * Units.LY }
            is PlanetBody -> target.star
            else -> null
        }
        if (star == null) {
            val current = activeSystem?.star
            if (current != null && (current.pos - cam).length() < 0.4 * Units.LY) star = current
        }
        if (star == null && fromSun > 0.3 * Units.LY) {
            val rs = realStars
            val hit = rs?.nearest(cam - UPos.ZERO, 0.1)
            if (hit != null) star = rs.bodyFrom(hit.first, hit.second)
            if (star == null) star = extraStarNear?.invoke(cam)
        }
        activeSystem = star?.let { s -> activeSystem?.takeIf { it.star.id == s.id } ?: systemFor(s) }
    }

    /** Hook for generated stars (set by the galaxy generator). */
    @Volatile var extraStarNear: ((UPos) -> StarBody?)? = null

    fun homeBody(): Body = earth

    /** Real Gaia DR3 stars streamed around the camera. */
    val gaia = GaiaStream(engine.context.cacheDir)
    private var gaiaTimer = 0.0

    private fun updateGaia(cam: UPos, dt: Double) {
        gaiaTimer += dt
        if (gaiaTimer < 0.5) return
        gaiaTimer = 0.0
        val rs = realStars ?: return
        rs.gaia = gaia
        val nearSphere = if (gaia.near != null) milkyWay.toLocal(UPos.ZERO).let { doubleArrayOf(it.x, it.y, it.z, GaiaStream.NEAR_PC) } else null
        if (!engine.settings.gaiaStream) {
            if (gaia.bubble != null) { gaia.clear(); genLocal = null }
            val want = listOfNotNull(nearSphere)
            if (StarField.gaiaSpheres.size != want.size) { StarField.gaiaSpheres = want; genLocal = null }
            return
        }
        if (StarField.gaiaSpheres.isEmpty() && nearSphere != null) { StarField.gaiaSpheres = listOf(nearSphere); genLocal = null }
        gaia.update(cam, rs) {
            val b = gaia.bubble
            val bs = b?.let { val c = milkyWay.toLocal(UPos.ZERO + it.centerPc * Units.PC); doubleArrayOf(c.x, c.y, c.z, it.radiusPc) }
            StarField.gaiaSpheres = listOfNotNull(nearSphere, bs)
            genLocal = null   // regenerate without the stars Gaia now provides
        }
    }

    fun update(engine: Engine, dt: Double) {
        val cam = synchronized(engine.camera) { engine.camera.position() }
        updateGaia(cam, dt)
        updateNearGalaxies(cam, dt)
        updateGenerated(cam, dt)
        updateRocks(cam, dt)
        updateNearest(cam, dt)
        systemTimer += dt
        if (systemTimer > 0.25) {
            systemTimer = 0.0
            updateActiveSystem()
        }
    }

    fun find(id: String): Body? {
        if (id.startsWith("fam:")) return Famous.find(id)
        byId[id]?.let { return it }
        byIdConcurrent[id]?.let { return it }
        if (id.startsWith("gal:out")) {
            galaxyIndex[id]?.let { return it }
            val i = id.removePrefix("gal:out").toIntOrNull() ?: return null
            val web = outerWeb ?: return null
            if (i !in 0 until web.count) return null
            return web.galaxy(i).also { registerExtraGalaxy(it) }
        }
        if (id.startsWith("gal:web")) {
            galaxyIndex[id]?.let { return it }
            val i = id.removePrefix("gal:web").toIntOrNull() ?: return null
            val web = cosmicWeb ?: return null
            if (i !in 0 until web.count) return null
            return web.galaxy(i).also { registerExtraGalaxy(it) }
        }
        if (id.startsWith("sba:") || id.startsWith("sbc:")) {
            val sb = smallBodies ?: return null
            return sb.indexOf(id.substring(4))?.let { sb.body(it) }
        }
        if (id.startsWith("ast:")) {
            val db = allAsteroids ?: return null
            return db.indexOfPacked(id.removePrefix("ast:"))?.let { asteroidBody(it) }
        }
        if (id.startsWith("net:")) return Online.find(id)
        if (id.startsWith("psr:") || id.startsWith("qso:") || id.startsWith("gcl:")) return exotic.find(id)
        if (id.startsWith("rem:")) return Remnants.fromId(id) { galaxyById(it) }
        if (id.startsWith("gal:f")) {
            galaxyIndex[id]?.let { return it }
            return GalaxyField.fromId(id)?.also { registerExtraGalaxy(it) }
        }
        if (id.startsWith("gal:")) return galaxyIndex[id]
        if (id.startsWith("gs:") && !id.contains("/")) return StarField.fromId(id) { galaxyById(it) }
        if (id.startsWith("gaia:") && !id.contains("/")) {
            val src = id.removePrefix("gaia:").toLongOrNull() ?: return null
            for (cat in listOfNotNull(gaia.near, gaia.bubble?.catalog)) {
                for (t in cat.tiles) for (i in 0 until t.count) if (gaia.sourceOf[t.ids[i]] == src) return realStars?.bodyFrom(t, i)
            }
            return gaia.lookup(src)
        }
        if (id.startsWith("hyg:") && !id.contains("/")) return id.removePrefix("hyg:").toIntOrNull()?.let { realStars?.body(it) }
        if (id.startsWith("rock|")) return RockField.fromId(id) { zonesForKey(it) }
        if (id.contains("/belt")) return Belts.fromId(id, { find(it) }, { systemFor(it) })
        if (id.contains("/")) {
            return when (val parent = find(id.substringBeforeLast("/"))) {
                is StarBody -> systemFor(parent).planets.firstOrNull { it.id == id }
                is PlanetBody -> parent.moons.firstOrNull { it.id == id }
                else -> null
            }
        }
        if (id.startsWith("exo:") && !id.contains("/m")) {
            val name = id.removePrefix("exo:")
            val cat = exo ?: return null
            for ((sid, rows) in exoForStar) {
                if (rows.none { it.name == name }) continue
                val star = find(sid) as? StarBody ?: return null
                return systemFor(star).planets.firstOrNull { it.id == id }
            }
            if (cat.planetCount == 0) return null
        }
        return null
    }

    /** Bodies the camera must not enter. */
    fun solidBodiesNear(cam: UPos, jd: Double): List<Body> {
        val out = ArrayList<Body>(4)
        for (b in solar) {
            val d = (b.position(jd) - cam).length()
            if (d < b.radius * 1.5 + 50_000) out.add(b)
        }
        if (engine.settings.showTestObjects && (testPlanet.position(jd) - cam).length() < Units.R_EARTH * 1.5) out.add(testPlanet)
        activeSystem?.let { sys ->
            if ((sys.star.pos - cam).length() < sys.star.radius * 1.5) out.add(sys.star)
            for (p in sys.planets) {
                if ((p.position(jd) - cam).length() < p.radius * 1.5 + 50_000) out.add(p)
                for (m in p.moons) if ((m.position(jd) - cam).length() < m.radius * 1.5 + 50_000) out.add(m)
            }
        }
        return out
    }

    /** Body orientation (body frame -> world), for bodies that spin. */
    fun orientationOf(body: Body, jd: Double): com.emotionalleaves.universe.core.Quatd? = when (body) {
        is SolarBody -> body.orientation(jd)
        is PlanetBody -> body.spin(jd)
        else -> null
    }

    fun testBodies(): List<Body> = listOf(testPlanet, testCube, farMarker)

    class TestPlanet(private val pos: UPos) : Body() {
        override val id = "test:planet"
        override val name = "Test planet"
        override val kind = Kind.PLANET
        override val source = Source.GENERATED
        override val radius = Units.R_EARTH
        override val subtitle get() = "Earth-sized sphere for the scale test"
        override fun position(jd: Double) = pos
    }
}
