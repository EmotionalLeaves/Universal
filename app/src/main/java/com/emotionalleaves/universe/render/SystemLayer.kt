package com.emotionalleaves.universe.render

import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import com.emotionalleaves.universe.core.smoothstep
import com.emotionalleaves.universe.engine.PlanetBody
import com.emotionalleaves.universe.engine.StarBody
import com.emotionalleaves.universe.engine.StarSystem
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Draws the star system you are visiting: the star as a sphere, its planets, and their orbits. */
class SystemLayer : Layer {
    private var built: StarSystem? = null
    private val orbits = HashMap<String, LineMesh>()
    var showOrbits = true

    override fun init(rc: RenderContext) {
        built = null
        orbits.clear()
        ringMesh = Meshes.ring(0.62f, 1f)
    }

    private fun system(rc: RenderContext) = rc.engine.world.activeSystem
    private var ringMesh: Mesh? = null

    /** Planets and their moons. */
    private fun bodies(sys: StarSystem): List<PlanetBody> = sys.planets.flatMap { listOf(it) + it.moons }

    override fun update(frame: Frame, rc: RenderContext) {
        val sys = system(rc)
        if (sys !== built) {
            for (m in orbits.values) m.release()
            orbits.clear()
            if (sys != null) {
                for (p in bodies(sys)) {
                    val n = 180
                    val pts = FloatArray((n + 1) * 3)
                    for (i in 0..n) {
                        val v = p.relative(frame.jd + p.periodDays * i / n)
                        pts[i * 3] = v.x.toFloat(); pts[i * 3 + 1] = v.y.toFloat(); pts[i * 3 + 2] = v.z.toFloat()
                    }
                    orbits[p.id] = LineMesh.upload(pts)
                }
            }
            built = sys
        }
    }

    fun starMaterial(star: StarBody): Material {
        val c = StarBody.tempColor(star.teff)
        return Material(Material.STAR, c, c, c, seed = (star.id.hashCode() % 997) / 13f, ambient = 1f)
    }

    fun planetMaterial(p: PlanetBody): Material = Material(
        p.type.style, Material.rgb(p.palette[0]), Material.rgb(p.palette[1]), Material.rgb(p.palette[2]),
        seed = ((p.seed % 10007) / 97.0).toFloat(), ambient = 0.03f,
    )

    override fun drawOpaque(frame: Frame, rc: RenderContext) {
        val sys = system(rc) ?: return
        val starRel = frame.rel(sys.star.pos)
        rc.drawer.beginOpaque()
        val spx = frame.pixelSize(sys.star.radius, starRel.length())
        if (spx > 0.7) {
            rc.drawer.sphere(frame, starRel, sys.star.radius, Quatd.IDENTITY, starMaterial(sys.star), Vec3d.Z,
                if (spx < 40) rc.programs.lowSphereMesh else rc.programs.sphereMesh)
        }
        for (p in bodies(sys)) {
            if (rc.engine.world.terrainActiveFor(p)) continue
            val rel = frame.rel(p.position(frame.jd))
            val px = frame.pixelSize(p.radius, rel.length())
            if (px < 0.7) continue
            rc.drawer.sphere(frame, rel, p.radius, p.spin(frame.jd), planetMaterial(p), starRel - rel,
                if (px < 40) rc.programs.lowSphereMesh else rc.programs.sphereMesh)
        }
        rc.drawer.end()
    }

    override fun drawTransparent(frame: Frame, rc: RenderContext) {
        val sys = system(rc) ?: return
        val starRel = frame.rel(sys.star.pos)
        val camFromStar = starRel.length()
        rc.drawer.beginAdditive()
        // Corona glow
        val c = StarBody.tempColor(sys.star.teff)
        rc.drawer.glow(frame, starRel, sys.star.radius * 5, c, 0.8f, minPixels = 0.0)
        // Orbits (moons: only when you are near their planet)
        if (showOrbits) {
            for (p in bodies(sys)) {
                val m = orbits[p.id] ?: continue
                val anchorRel = p.host?.let { frame.rel(it.position(frame.jd)) } ?: starRel
                val fromAnchor = anchorRel.length()
                val far = 1.0 - smoothstep(p.aM * 30, p.aM * 200, fromAnchor)
                val near = smoothstep(p.radius * 30, p.radius * 400, frame.rel(p.position(frame.jd)).length())
                val a = ((if (p.host != null) 0.25 else 0.32) * far * near).toFloat()
                if (a > 0.01) rc.drawer.lines(frame, m, anchorRel, 1.0, Quatd.IDENTITY, Material.rgb(p.palette[2]), a)
            }
        }
        rc.drawer.end()
        // Rings (alpha blended)
        rc.drawer.beginAlpha()
        for (p in sys.planets) {
            if (p.ringOuter <= 0) continue
            val rel = frame.rel(p.position(frame.jd))
            if (frame.pixelSize(p.radius, rel.length()) < 1.5) continue
            rc.drawer.ring(frame, ringMesh!!, rel, p.spin(frame.jd), p.radius * p.ringOuter, 0,
                Material.rgb(p.palette[2]), p.radius, starRel - rel)
        }
        rc.drawer.end()
        rc.drawer.beginAdditive()
        // Atmospheres and point glows for small planets
        for (p in bodies(sys)) {
            val rel = frame.rel(p.position(frame.jd))
            val dist = rel.length()
            val px = frame.pixelSize(p.radius, dist)
            if (p.hasAtmosphere && px > 3 && dist > p.radius * 1.04) {
                rc.drawer.atmosphere(frame, rel, p.radius * 1.03, Material.rgb(p.palette[2]), starRel - rel, 0.9f)
            }
            if (px < 4) {
                // Reflected light: rough brightness from size and distances.
                val rs = (p.position(frame.jd) - sys.star.pos).length() / Units.AU
                val ro = dist / Units.AU
                val h = 5.0 * kotlin.math.log10(Units.R_EARTH * 1.3 / p.radius) - 3.9
                val m = h + 5.0 * kotlin.math.log10(max(rs * ro, 1e-9)) - 2.5 * kotlin.math.log10(max(sys.star.luminosity, 1e-6))
                val bright = 10.0.pow(-0.4 * (m - 7.0))
                if (bright < 0.02) continue
                val alpha = (min(1.0, bright) * (1.0 - smoothstep(2.0, 4.0, px))).toFloat()
                rc.drawer.glow(frame, rel, 0.0, Material.rgb(p.palette[2]), alpha, minPixels = min(12.0, 2.5 + 2 * bright.pow(0.25)))
            }
        }
        rc.drawer.end()
    }

    override fun collect(frame: Frame, rc: RenderContext, out: MutableList<Pickable>) {
        val sys = system(rc) ?: return
        val starRel = frame.rel(sys.star.pos)
        val camFromStar = starRel.length()
        frame.project(starRel)?.let { s ->
            val px = frame.pixelSize(sys.star.radius, camFromStar).toFloat()
            out.add(Pickable(sys.star, s.first, s.second, max(px, 8f), 9f, true, 0xFFFFE6B0.toInt()))
        }
        for (p in bodies(sys)) {
            val rel = frame.rel(p.position(frame.jd))
            val s = frame.project(rel) ?: continue
            val px = frame.pixelSize(p.radius, rel.length()).toFloat()
            val host = p.host
            val label = if (host != null) (host.position(frame.jd) - frame.camPos).length() < p.aM * 25 else camFromStar < p.aM * 300
            if (host != null && !label && px < 2 && p != rc.engine.selected) continue
            out.add(Pickable(p, s.first, s.second, max(px, 6f), 7f, label,
                if (p.source.name == "REAL") 0xFFBFF0D0.toInt() else 0xFFDCCBFF.toInt()))
        }
    }
}
