package com.emotionalleaves.universe.render

import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import com.emotionalleaves.universe.core.smoothstep
import com.emotionalleaves.universe.engine.Kind
import com.emotionalleaves.universe.engine.SolarBody
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Draws the Sun, planets, moons, rings, atmospheres and orbit lines. */
class SolarSystemLayer : Layer {
    private val orbitMeshes = HashMap<String, LineMesh>()
    private var circle: LineMesh? = null
    private var ringMesh: Mesh? = null
    private var orbitsBuiltJd = Double.NaN
    var showOrbits = true

    override fun init(rc: RenderContext) {
        orbitMeshes.clear()
        orbitsBuiltJd = Double.NaN
        val pts = FloatArray(129 * 3)
        for (i in 0..128) {
            val a = 2 * Math.PI * i / 128
            pts[i * 3] = cos(a).toFloat(); pts[i * 3 + 1] = sin(a).toFloat(); pts[i * 3 + 2] = 0f
        }
        circle = LineMesh.upload(pts)
        ringMesh = Meshes.ring(74_500f / 140_220f, 1f)
    }

    private fun bodies(rc: RenderContext) = rc.engine.world.solar

    override fun update(frame: Frame, rc: RenderContext) {
        // Planet orbit ellipses relative to the Sun, rebuilt every simulated decade.
        if (orbitsBuiltJd.isNaN() || kotlin.math.abs(frame.jd - orbitsBuiltJd) > 3650) {
            for (m in orbitMeshes.values) m.release()
            orbitMeshes.clear()
            for (b in bodies(rc)) {
                if (b.parent?.id != "sol:sun") continue
                val n = 360
                val pts = FloatArray((n + 1) * 3)
                val period = b.orbitPeriodDays
                for (i in 0..n) {
                    val p = b.relative(frame.jd + period * i / n)
                    pts[i * 3] = p.x.toFloat(); pts[i * 3 + 1] = p.y.toFloat(); pts[i * 3 + 2] = p.z.toFloat()
                }
                orbitMeshes[b.id] = LineMesh.upload(pts)
            }
            orbitsBuiltJd = frame.jd
        }
    }

    /** How strongly the solar system is shown: fades out once you are far outside it. */
    private fun systemVisibility(frame: Frame, rc: RenderContext): Double {
        val sun = bodies(rc).firstOrNull() ?: return 0.0
        val d = (sun.position(frame.jd) - frame.camPos).length()
        return 1.0 - smoothstep(2_000 * Units.AU, 40_000 * Units.AU, d)
    }

    override fun drawOpaque(frame: Frame, rc: RenderContext) {
        val vis = systemVisibility(frame, rc)
        val list = bodies(rc)
        if (list.isEmpty()) return
        val sunRel = frame.rel(list[0].position(frame.jd))
        rc.drawer.beginOpaque()
        for (b in list) {
            if (vis <= 0.0 && b.kind != Kind.STAR) continue
            val rel = frame.rel(b.position(frame.jd))
            val dist = rel.length()
            val px = frame.pixelSize(b.radius, dist)
            if (px < 0.7) continue
            if (rc.engine.world.terrainActiveFor(b)) continue // terrain layer draws it up close
            val light = if (b.kind == Kind.STAR) Vec3d.Z else (sunRel - rel)
            val mat = material(b, rc)
            val mesh = if (px < 40) rc.programs.lowSphereMesh else rc.programs.sphereMesh
            rc.drawer.sphere(frame, rel, b.radius, b.orientation(frame.jd), mat, light, mesh)
        }
        rc.drawer.end()
    }

    override fun drawTransparent(frame: Frame, rc: RenderContext) {
        val list = bodies(rc)
        if (list.isEmpty()) return
        val vis = systemVisibility(frame, rc)
        val sun = list[0]
        val sunRel = frame.rel(sun.position(frame.jd))
        val camFromSun = sunRel.length()

        // Rings and clouds (alpha blended)
        if (vis > 0) {
            rc.drawer.beginAlpha()
            for (b in list) {
                val rel = frame.rel(b.position(frame.jd))
                val dist = rel.length()
                val px = frame.pixelSize(b.radius, dist)
                if (px < 1.5) continue
                val light = sunRel - rel
                if (b.look.ringOuter > 0) {
                    val tex = b.look.ringTexture?.let { rc.textures.get(it) } ?: 0
                    rc.drawer.ring(frame, ringMesh!!, rel, b.orientation(frame.jd), b.look.ringOuter, tex,
                        Material.rgb(b.look.colorA), b.radius, light)
                }
                val clouds = b.look.clouds
                if (clouds != null && px > 4) {
                    val tex = rc.textures.get(clouds)
                    if (tex != 0) {
                        val drift = Quatd.axisAngle(Vec3d.Z, frame.jd * 0.02 % (2 * Math.PI))
                        val m = Material(Material.PLAIN, texture = tex, ambient = 0.02f)
                        rc.drawer.sphere(frame, rel, b.radius * 1.006, b.orientation(frame.jd) * drift, m, light,
                            if (px < 40) rc.programs.lowSphereMesh else rc.programs.sphereMesh, alphaMode = 1)
                    }
                }
            }
            rc.drawer.end()
        }

        rc.drawer.beginAdditive()
        // Orbit lines
        if (showOrbits && vis > 0) {
            for (b in list) {
                val parent = b.parent ?: continue
                val parentRel = frame.rel(parent.position(frame.jd))
                if (parent.id == "sol:sun") {
                    val mesh = orbitMeshes[b.id] ?: continue
                    val scale = b.orbitRadius
                    // Fade when far away from the whole orbit, or when hugging the planet itself.
                    val farFade = 1.0 - smoothstep(scale * 60, scale * 400, camFromSun)
                    val near = (frame.rel(b.position(frame.jd))).length()
                    val nearFade = smoothstep(b.radius * 30, b.radius * 400, near)
                    val a = (0.35 * farFade * nearFade * vis).toFloat()
                    if (a > 0.01) rc.drawer.lines(frame, mesh, parentRel, 1.0, Quatd.IDENTITY, Material.rgb(b.color), a)
                } else {
                    // Moons: circle through the moon in its orbit plane.
                    val r = b.relative(frame.jd)
                    val v = b.relative(frame.jd + 0.01) - r
                    val normal = r.cross(v).normalized()
                    val rot = Quatd.fromBasis(r.normalized(), normal.cross(r.normalized()).normalized(), normal)
                    val camToParent = parentRel.length()
                    // Hundreds of small moons: show their orbits only close in, or when selected.
                    val big = b.radius > 150_000 || b == rc.engine.selected
                    val far = if (big) 25.0 to 120.0 else 1.5 to 4.0
                    val a = (0.28 * (1.0 - smoothstep(b.orbitRadius * far.first, b.orbitRadius * far.second, camToParent))).toFloat()
                    if (a > 0.01) rc.drawer.lines(frame, circle!!, parentRel, r.length(), rot, Material.rgb(b.color), a)
                }
            }
        }
        // Atmospheres
        for (b in list) {
            if (b.look.atmosphereStrength <= 0f || vis <= 0) continue
            val rel = frame.rel(b.position(frame.jd))
            val px = frame.pixelSize(b.radius, rel.length())
            if (px < 3 || rel.length() < b.radius * 1.03) continue
            rc.drawer.atmosphere(frame, rel, b.radius * 1.025, Material.rgb(b.look.atmosphere), sunRel - rel, b.look.atmosphereStrength)
        }
        // Point glows for bodies too small to see as discs, and the Sun's corona.
        for (b in list) {
            val rel = frame.rel(b.position(frame.jd))
            val dist = rel.length()
            val px = frame.pixelSize(b.radius, dist)
            if (b.kind == Kind.STAR) {
                val m = b.apparentMag(frame.jd, frame.camPos)
                val bright = 10.0.pow(-0.4 * (m - 6.0))
                val glowPx = min(160.0, 4.0 * bright.pow(0.22))
                val corona = max(b.radius * 5.0, 0.0)
                rc.drawer.glow(frame, rel, corona, floatArrayOf(1f, 0.85f, 0.6f), 0.8f, minPixels = glowPx)
                continue
            }
            if (vis <= 0) continue
            if (px > 4) continue
            val m = b.apparentMag(frame.jd, frame.camPos)
            val bright = 10.0.pow(-0.4 * (m - 7.0))
            if (bright < 0.02) continue
            val alpha = (min(1.0, bright) * vis * (1.0 - smoothstep(2.0, 4.0, px))).toFloat()
            val size = min(12.0, 2.5 + 2.0 * bright.pow(0.25))
            rc.drawer.glow(frame, rel, 0.0, Material.rgb(b.color), alpha, minPixels = size)
        }
        rc.drawer.end()
    }

    override fun collect(frame: Frame, rc: RenderContext, out: MutableList<Pickable>) {
        val list = bodies(rc)
        if (list.isEmpty()) return
        val vis = systemVisibility(frame, rc)
        val sunRel = frame.rel(list[0].position(frame.jd))
        val camFromSun = sunRel.length()
        for (b in list) {
            if (vis <= 0 && b.kind != Kind.STAR) continue
            val rel = frame.rel(b.position(frame.jd))
            val s = frame.project(rel) ?: continue
            val px = frame.pixelSize(b.radius, rel.length()).toFloat()
            if (b.kind == Kind.STAR && camFromSun > 3000 * Units.LY) {
                out.add(Pickable(b, s.first, s.second, 8f, 10f, true, 0xFFFFD27A.toInt(), "You are here (Sun)"))
                continue
            }
            val label = when (b.kind) {
                Kind.STAR -> camFromSun < 2 * Units.LY
                Kind.PLANET -> camFromSun < 400 * Units.AU
                Kind.DWARF_PLANET -> camFromSun < 120 * Units.AU
                else -> {
                    // Big moons are named from afar; the hundreds of small ones only up close.
                    val parentDist = (b.parent!!.position(frame.jd) - frame.camPos).length()
                    if (b.radius > 150_000) parentDist < b.orbitRadius * 30
                    else parentDist < b.orbitRadius * 3 && parentDist < b.parent.radius * 400
                }
            }
            val pri = when (b.kind) {
                Kind.STAR -> 9f
                Kind.PLANET -> 8f
                Kind.DWARF_PLANET -> 6f
                else -> 4f + (b.radius / 3e6).toFloat()
            }
            if (!label && b != rc.engine.selected && px < 2) continue
            out.add(Pickable(b, s.first, s.second, max(px, 6f), pri, label, 0xFFDDE6FF.toInt()))
        }
    }

    private fun material(b: SolarBody, rc: RenderContext): Material {
        val l = b.look
        val tex = l.texture?.let { rc.textures.get(it) } ?: 0
        val night = l.night?.let { rc.textures.get(it) } ?: 0
        return Material(
            l.style, Material.rgb(l.colorA), Material.rgb(l.colorB), Material.rgb(l.colorC),
            seed = (b.id.hashCode() % 1000) / 37f, texture = tex, night = night,
            ambient = if (b.kind == Kind.STAR) 1f else 0.025f,
        )
    }
}
