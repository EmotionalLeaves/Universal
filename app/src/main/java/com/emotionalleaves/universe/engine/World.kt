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

    fun start() {
        for (b in solar) byId[b.id] = b
        for (b in testBodies()) byId[b.id] = b
    }

    fun homeBody(): Body = earth

    fun update(engine: Engine) {}

    fun find(id: String): Body? = byId[id]

    /** Bodies the camera must not enter. */
    fun solidBodiesNear(cam: UPos, jd: Double): List<Body> {
        val out = ArrayList<Body>(4)
        for (b in solar) {
            val d = (b.position(jd) - cam).length()
            if (d < b.radius * 1.5 + 50_000) out.add(b)
        }
        if (engine.settings.showTestObjects && (testPlanet.position(jd) - cam).length() < Units.R_EARTH * 1.5) out.add(testPlanet)
        return out
    }

    /** Terrain height above the body's radius in direction [fromCentre] (0 for smooth spheres). */
    fun surfaceHeight(body: Body, fromCentre: Vec3d): Double = 0.0

    /** True when the terrain layer is drawing [body] instead of the plain sphere. */
    fun terrainActiveFor(body: Body): Boolean = false

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
