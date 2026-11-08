package com.emotionalleaves.universe.engine

import com.emotionalleaves.universe.core.Astro
import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.core.Rng
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import java.util.Locale
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Generated asteroid belts and icy outer belts (like our Kuiper belt) around other stars.
 * Each belt is a ring of rocks in the plane of the system's planets; every rock can be tapped.
 */
class Belt(val star: StarBody, val index: Int, val innerAu: Double, val outerAu: Double, val icy: Boolean, val count: Int, val frame: Quatd, private val seed: Long) {
    /** Points relative to the star, in AU: x, y, z, r, g, b, absMag-for-display (7 floats each). */
    val data: FloatArray by lazy { build() }
    private val hs = FloatArray(count)

    val label get() = if (icy) "outer icy belt" else "asteroid belt"

    private fun build(): FloatArray {
        val rng = Rng(seed)
        val out = FloatArray(count * 7)
        val pcAu = (5 * log10(Units.PC / Units.AU) + 5).toFloat()
        for (i in 0 until count) {
            val a = innerAu + (outerAu - innerAu) * rng.nextDouble()
            val t = rng.nextDouble() * 2 * Math.PI
            val z = (rng.nextDouble() - 0.5) * a * (if (icy) 0.25 else 0.12)
            val p = frame.rotate(Vec3d(a * cos(t), a * sin(t), z))
            val h = if (icy) 6.0 + 6.0 * rng.nextDouble() else 11.0 + 6.0 * rng.nextDouble().pow(0.6)
            hs[i] = h.toFloat()
            out[i * 7] = p.x.toFloat(); out[i * 7 + 1] = p.y.toFloat(); out[i * 7 + 2] = p.z.toFloat()
            if (icy) { out[i * 7 + 3] = 0.75f; out[i * 7 + 4] = 0.82f; out[i * 7 + 5] = 1f } else { out[i * 7 + 3] = 0.85f; out[i * 7 + 4] = 0.78f; out[i * 7 + 5] = 0.68f }
            out[i * 7 + 6] = (h + 5 * log10(max(a, 0.05)) + pcAu).toFloat()
        }
        return out
    }

    fun localAu(i: Int): Vec3d { val d = data; return Vec3d(d[i * 7].toDouble(), d[i * 7 + 1].toDouble(), d[i * 7 + 2].toDouble()) }

    fun body(i: Int): BeltBody? {
        if (i !in 0 until count) return null
        if (data.isEmpty()) return null   // builds the ring (and sizes) on first use
        val h = hs[i].toDouble()
        val km = 1329.0 / sqrt(if (icy) 0.09 else 0.14) * 10.0.pow(-0.2 * h)
        return BeltBody(this, i, star.pos + localAu(i) * Units.AU, km * 500)
    }
}

class BeltBody(val belt: Belt, val index: Int, private val pos: UPos, override val radius: Double) : Body() {
    override val id = "${belt.star.id}/belt${belt.index}/$index"
    override val name = String.format(Locale.US, "%s %s-%d", belt.star.name, if (belt.icy) "K" else "A", index)
    override val kind = Kind.ASTEROID
    override val source = Source.GENERATED
    override val color = if (belt.icy) 0xC0D0FF else 0xC8B8A0
    override fun position(jd: Double) = pos
    override val subtitle get() = "${if (belt.icy) "Icy body" else "Asteroid"} in ${belt.star.name}'s ${belt.label}"
    override fun viewDistance() = max(radius * 6.0, 2.0e4)
    override fun facts(jd: Double): List<Pair<String, String>> = listOf(
        "Size" to String.format(Locale.US, "about %,.1f km across", radius * 2 / 1000),
        "From its star" to String.format(Locale.US, "%.2f AU", belt.localAu(index).length()),
        "Belt" to String.format(Locale.US, "%s, %.1f–%.1f AU wide ring of %,d bodies shown", belt.label, belt.innerAu, belt.outerAu, belt.count),
        "Data" to "Generated (belts are common around other stars: many show warm or cold dust rings)",
    )
}

object Belts {
    private val cache = object : LinkedHashMap<String, List<Belt>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Belt>>?) = size > 12
    }

    fun forSystem(sys: StarSystem): List<Belt> = synchronized(cache) {
        cache.getOrPut(sys.star.id) { build(sys) }
    }

    private fun build(sys: StarSystem): List<Belt> {
        val star = sys.star
        val rng = Rng(Rng.hash(Rng.hashString(star.id), 0xBE17L, SystemFactory.GENERATOR_VERSION.toLong()))
        // The planets' plane if there are planets, otherwise any plane.
        val n = sys.planets.firstOrNull()?.let { p ->
            val a = p.relative(Astro.J2000); val b = p.relative(Astro.J2000 + p.periodDays / 4)
            a.cross(b).let { if (it.lengthSq() > 0) it.normalized() else null }
        } ?: rng.unitVector()
        val x = Vec3d.perpendicular(n)
        val frame = Quatd.fromBasis(x, n.cross(x).normalized(), n)
        val l = star.luminosity.coerceIn(1e-4, 1e5)
        val snow = 2.7 * sqrt(l)
        val orbits = sys.planets.map { it.aM / Units.AU }
        val out = ArrayList<Belt>()
        fun clear(i: Double, o: Double) = orbits.none { it in i * 0.85..o * 1.15 }
        if (rng.nextDouble() < 0.55) {
            val i = snow * 0.75; val o = snow * 1.1
            if (clear(i, o)) out.add(Belt(star, out.size, i, o, false, 2400, frame, rng.nextLong()))
        }
        if (rng.nextDouble() < 0.6) {
            val i = snow * 11; val o = snow * 18
            if (clear(i, o)) out.add(Belt(star, out.size, i, o, true, 1800, frame, rng.nextLong()))
        }
        return out
    }

    /** id: <starId>/belt<k>/<i> */
    fun fromId(id: String, find: (String) -> Body?, systemFor: (StarBody) -> StarSystem): Body? {
        val m = Regex("""^(.*)/belt(\d+)/(\d+)$""").matchEntire(id) ?: return null
        val star = find(m.groupValues[1]) as? StarBody ?: return null
        return forSystem(systemFor(star)).getOrNull(m.groupValues[2].toInt())?.body(m.groupValues[3].toInt())
    }
}
