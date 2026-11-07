package com.emotionalleaves.universe.engine

import android.content.Context
import com.emotionalleaves.universe.core.Astro
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units

/** Owns the simulation state: camera, time and what is selected. Rendering lives in UniverseRenderer. */
class Engine(val context: Context) {
    val camera = CameraController()

    /** Simulation time as a Julian date. */
    @Volatile var jd: Double = Astro.julianDate(System.currentTimeMillis())
    @Volatile var timeRate: Double = 1.0
    @Volatile var paused: Boolean = false

    /** Demo body for Round 1: one lit planet at the origin. */
    val demoPlanet = MarkerBody("demo:planet", "Test planet", UPos.ZERO, Units.R_EARTH, "Round 1 test sphere")

    init {
        camera.jumpTo(demoPlanet, UPos.ZERO, Units.R_EARTH * 3.5)
    }

    fun update(dt: Double, now: Double) {
        if (!paused) jd += dt * timeRate / 86400.0
        synchronized(camera) {
            camera.update(dt, now) { it.position(jd) }
        }
    }

    fun onUserInteraction() {}

    fun onTap(x: Float, y: Float) {}

    fun onDoubleTap(x: Float, y: Float) {}
}
