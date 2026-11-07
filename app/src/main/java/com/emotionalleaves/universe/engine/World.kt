package com.emotionalleaves.universe.engine

import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d

/** Everything that exists: catalogs, generated content and the scale-test objects. */
class World(private val engine: Engine) {

    // ---- Scale-test scene (Round 2): a 1 m cube on an Earth-sized sphere, and a marker 10 Gly away.
    val testPlanet = TestPlanet()
    private val cubeDir = Vec3d.fromRaDec(15.0, 35.0)
    val testCube = MarkerBody(
        "test:cube", "1 m cube", UPos.ZERO + cubeDir * (Units.R_EARTH + 0.5), 0.5, "Scale test: 1 metre across",
    )
    val farMarker = MarkerBody(
        "test:far", "10 billion light years", UPos.ZERO + Vec3d.fromRaDec(83.8, -5.4) * (1e10 * Units.LY), 0.0,
        "Scale test marker",
    )
    val cubeOrientationUp: Vec3d get() = cubeDir

    fun start() {}

    fun homeBody(): Body = testCube

    fun update(engine: Engine) {}

    /** Bodies the camera must not enter. */
    fun solidBodiesNear(cam: UPos, jd: Double): List<Body> = listOf(testPlanet)

    /** Terrain height above the body's radius in direction [fromCentre] (0 for smooth spheres). */
    fun surfaceHeight(body: Body, fromCentre: Vec3d): Double = 0.0

    fun testBodies(): List<Body> = listOf(testPlanet, testCube, farMarker)

    class TestPlanet : Body() {
        override val id = "test:planet"
        override val name = "Test planet"
        override val kind = Kind.PLANET
        override val source = Source.GENERATED
        override val radius = Units.R_EARTH
        override val subtitle get() = "Earth-sized sphere for the scale test"
        override fun position(jd: Double) = UPos.ZERO
    }
}
