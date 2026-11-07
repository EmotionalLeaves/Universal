package com.emotionalleaves.universe.engine

import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d

/** Everything that exists: catalogs, generated content and the scale-test objects. */
class World(private val engine: Engine) {

    /** The Sun first, then planets and moons. */
    val solar: List<SolarBody> = SolarSystem.build()
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

    private val byId = HashMap<String, Body>()
    private val loader = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Real star catalogues; null until loaded (or if the data is missing). */
    @Volatile var realStars: RealStars? = null
        private set
    @Volatile var starsFailed = false
        private set
    @Volatile var exo: ExoCatalog? = null
        private set
    private val exoForStar = java.util.concurrent.ConcurrentHashMap<String, List<ExoRow>>()

    /** The star system the camera is in (other than our own), with its planets. */
    @Volatile var activeSystem: StarSystem? = null
        private set
    private val systemCache = object : LinkedHashMap<String, StarSystem>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StarSystem>?) = size > 12
    }
    private var systemTimer = 0.0

    fun start() {
        for (b in solar) byId[b.id] = b
        for (b in testBodies()) byId[b.id] = b
        loader.execute {
            try {
                realStars = RealStars.load(engine.context)
            } catch (e: Throwable) {
                android.util.Log.w("World", "Star catalogue failed: $e")
            }
            if (realStars == null) starsFailed = true
            try {
                loadExoplanets()
            } catch (e: Throwable) {
                android.util.Log.w("World", "Exoplanets failed: $e")
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

    fun update(engine: Engine, dt: Double) {
        systemTimer += dt
        if (systemTimer > 0.25) {
            systemTimer = 0.0
            updateActiveSystem()
        }
    }

    fun find(id: String): Body? {
        byId[id]?.let { return it }
        if (id.startsWith("hyg:") && !id.contains("/")) return id.removePrefix("hyg:").toIntOrNull()?.let { realStars?.body(it) }
        if (id.contains("/")) {
            val star = find(id.substringBeforeLast("/")) as? StarBody ?: return null
            return systemFor(star).planets.firstOrNull { it.id == id }
        }
        if (id.startsWith("exo:")) {
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
            for (p in sys.planets) if ((p.position(jd) - cam).length() < p.radius * 1.5 + 50_000) out.add(p)
        }
        return out
    }

    // ------------------------------------------------------------------ terrain

    private val terrainModels = java.util.concurrent.ConcurrentHashMap<String, TerrainModel>()
    private val noTerrain = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val terrainLoading = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Terrain model for a solid world; null while loading or for gas giants. */
    fun terrainModel(body: Body): TerrainModel? {
        terrainModels[body.id]?.let { return it }
        if (body.id in noTerrain) return null
        if (terrainLoading.add(body.id)) {
            loader.execute {
                val m = try { TerrainModel.forBody(engine.context, body) } catch (_: Throwable) { null }
                if (m != null) terrainModels[body.id] = m else noTerrain.add(body.id)
                terrainLoading.remove(body.id)
            }
        }
        return null
    }

    /** Body orientation (body frame -> world), for bodies that spin. */
    fun orientationOf(body: Body, jd: Double): com.emotionalleaves.universe.core.Quatd? = when (body) {
        is SolarBody -> body.orientation(jd)
        is PlanetBody -> body.spin(jd)
        else -> null
    }

    /** Terrain height above the body's radius in direction [fromCentre] (world frame). */
    fun surfaceHeight(body: Body, fromCentre: Vec3d): Double {
        val m = terrainModels[body.id] ?: return 0.0
        val q = orientationOf(body, engine.jd) ?: return 0.0
        val local = q.conjugate().rotate(fromCentre).normalized()
        return m.height(local)
    }

    /** Set by the terrain layer: the body whose ground is drawn as terrain tiles. */
    @Volatile var terrainBodyId: String? = null

    /** True when the terrain layer is drawing [body] instead of the plain sphere. */
    fun terrainActiveFor(body: Body): Boolean = terrainBodyId == body.id

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
