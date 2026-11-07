package com.emotionalleaves.universe.engine

import com.emotionalleaves.universe.core.Astro
import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.core.Rng
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/** Visual description of a solar-system body. */
class Look(
    val texture: String? = null,
    val night: String? = null,
    val clouds: String? = null,
    val style: Int = 0,
    val colorA: Int = 0x999999,
    val colorB: Int = 0x777777,
    val colorC: Int = 0xBBBBBB,
    val atmosphere: Int = 0,
    val atmosphereStrength: Float = 0f,
    val ringInner: Double = 0.0,
    val ringOuter: Double = 0.0,
    val ringTexture: String? = null,
    val heights: String? = null,
)

/** The Sun, planets, dwarf planets and major moons with real orbits (JPL elements) and sizes. */
class SolarBody(
    override val id: String,
    override val name: String,
    override val kind: Kind,
    override val radius: Double,
    val parent: SolarBody?,
    private val relPosition: (Double) -> Vec3d,
    val poleRa: Double,
    val poleDec: Double,
    val w0: Double,
    val wRate: Double,
    val tidallyLocked: Boolean,
    val look: Look,
    val absMagH: Double,
    val orbitPeriodDays: Double,
    val orbitRadius: Double,
    val massKg: Double,
    val facts0: List<Pair<String, String>>,
    override val color: Int,
) : Body() {
    override val source = Source.REAL
    val children = ArrayList<SolarBody>()

    override fun position(jd: Double): UPos {
        val p = parent ?: return UPos.ZERO
        return p.position(jd) + relPosition(jd)
    }

    /** Position relative to the parent (metres). */
    fun relative(jd: Double): Vec3d = relPosition(jd)

    fun heliocentric(jd: Double): Vec3d = position(jd) - UPos.ZERO

    fun orientation(jd: Double): Quatd {
        if (tidallyLocked && parent != null) {
            val toParent = -relPosition(jd)
            val pole = Vec3d.fromRaDec(poleRa, poleDec)
            var x = toParent - pole * toParent.dot(pole)
            if (x.lengthSq() < 1e-6) x = Vec3d.perpendicular(pole)
            x = x.normalized()
            return Quatd.fromBasis(x, pole.cross(x).normalized(), pole)
        }
        val spin = if (id == "sol:earth") Astro.gmst(jd) else Math.toRadians(w0 + wRate * (jd - Astro.J2000))
        return Astro.bodyOrientation(poleRa, poleDec, spin)
    }

    override val subtitle: String
        get() = when {
            parent == null -> "Our star"
            kind == Kind.MOON -> "Moon of ${parent.name}"
            else -> "${kind.label} of the Sun"
        }

    override fun viewDistance() = radius * if (kind == Kind.STAR) 4.5 else 3.2

    override fun facts(jd: Double): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out.add("Radius" to "${fmt(radius / 1000)} km")
        if (parent != null && kind != Kind.MOON) {
            val d = heliocentric(jd).length()
            out.add("From the Sun" to "${String.format(Locale.US, "%.3f", d / Units.AU)} AU")
        }
        if (kind == Kind.MOON && parent != null) {
            out.add("From ${parent.name}" to "${fmt(relPosition(jd).length() / 1000)} km")
        }
        if (orbitPeriodDays != 0.0) out.add("Orbit" to period(abs(orbitPeriodDays)))
        if (wRate != 0.0 && !tidallyLocked) out.add("Day length" to period(abs(360.0 / wRate)))
        if (tidallyLocked) out.add("Rotation" to "Always shows the same face")
        if (children.isNotEmpty()) out.add("Major moons" to children.joinToString(", ") { it.name })
        out.addAll(facts0)
        out.add("Data" to "JPL orbital elements, IAU rotation")
        return out
    }

    /** Rough apparent magnitude seen from [observer] (ignores phase). */
    fun apparentMag(jd: Double, observer: UPos): Double {
        if (kind == Kind.STAR) {
            val d = (position(jd) - observer).length() / Units.PC
            return 4.83 + 5 * log10(d.coerceAtLeast(1e-12)) - 5
        }
        val rs = (heliocentric(jd).length() / Units.AU).coerceAtLeast(1e-6)
        val ro = ((position(jd) - observer).length() / Units.AU).coerceAtLeast(1e-9)
        return absMagH + 5 * log10(rs * ro)
    }

    companion object {
        fun fmt(v: Double) = String.format(Locale.US, "%,.0f", v)
        fun period(days: Double): String = when {
            days < 2 -> String.format(Locale.US, "%.1f hours", days * 24)
            days < 800 -> String.format(Locale.US, "%.1f days", days)
            else -> String.format(Locale.US, "%.1f years", days / 365.25)
        }
    }
}

object SolarSystem {
    private const val KM = 1000.0

    fun build(): List<SolarBody> {
        val all = ArrayList<SolarBody>()
        val sun = SolarBody(
            "sol:sun", "Sun", Kind.STAR, 695_700 * KM, null, { Vec3d.ZERO }, 286.13, 63.87, 84.176, 14.1844, false,
            Look(texture = "textures/sun.jpg", style = 5, colorA = 0xFFE7B0), 4.83, 0.0, 0.0, 1.989e30,
            listOf("Type" to "G2V yellow dwarf", "Surface" to "5,772 K", "Age" to "4.6 billion years"), 0xFFF1D0,
        )
        all.add(sun)

        fun planet(
            id: String, name: String, kind: Kind, rKm: Double, el: Astro.Elements, ra: Double, dec: Double,
            w0: Double, wRate: Double, look: Look, h: Double, mass: Double, color: Int, facts: List<Pair<String, String>>,
        ): SolarBody {
            val b = SolarBody(
                "sol:$id", name, kind, rKm * KM, sun, { jd -> el.position(jd) }, ra, dec, w0, wRate, false,
                look, h, el.periodDays(), el.a * Units.AU, mass, facts, color,
            )
            sun.children.add(b); all.add(b); return b
        }

        val mercury = planet("mercury", "Mercury", Kind.PLANET, 2439.7, Astro.MERCURY, 281.01, 61.42, 329.5469, 6.1385025,
            Look("textures/mercury.jpg", style = 0, colorA = 0x8C8680, colorB = 0x6E6862, colorC = 0xA8A29A, heights = null),
            -0.6, 3.301e23, 0xB5ADA4, listOf("Surface temperature" to "−180 to 430 °C"))
        val venus = planet("venus", "Venus", Kind.PLANET, 6051.8, Astro.VENUS, 272.76, 67.16, 160.20, -1.4813688,
            Look("textures/venus.jpg", style = 1, colorA = 0xE8D3A0, colorB = 0xD8BC80, colorC = 0xF0E2BC,
                atmosphere = 0xFFE7B0, atmosphereStrength = 1.4f),
            -4.4, 4.867e24, 0xF2E1B5, listOf("Atmosphere" to "Thick CO₂, 92 bar", "Surface" to "465 °C"))
        // Earth and Moon orbit their common centre (the EM barycentre in the JPL table).
        val moonMassRatio = 1.0 / 82.300
        val earth = SolarBody(
            "sol:earth", "Earth", Kind.PLANET, 6371.0 * KM, sun,
            { jd -> Astro.EM_BARY.position(jd) - Astro.moonGeocentric(jd) * (moonMassRatio / (1 + moonMassRatio)) },
            0.0, 90.0, 0.0, 360.9856235, false,
            Look("textures/earth.jpg", night = "textures/earth_night.jpg", clouds = "textures/earth_clouds.png",
                style = 4, colorA = 0x1A3D7A, colorB = 0x3F6B2E, colorC = 0x9E8F6E,
                atmosphere = 0x6AA6FF, atmosphereStrength = 1.6f, heights = "heights/earth.bin"),
            -3.99, Astro.EM_BARY.periodDays(), Units.AU, 5.972e24,
            listOf("Population" to "About 8.2 billion", "Atmosphere" to "Nitrogen, oxygen"), 0x6FA0FF,
        )
        sun.children.add(earth); all.add(earth)
        val moon = SolarBody(
            "sol:moon", "Moon", Kind.MOON, 1737.4 * KM, earth, { jd -> Astro.moonGeocentric(jd) },
            269.9949, 66.5392, 38.3213, 13.17635815, true,
            Look("textures/moon.jpg", style = 0, colorA = 0x8A8A8A, colorB = 0x6A6A6A, colorC = 0xA0A0A0,
                heights = "heights/moon.bin"),
            0.21, 27.321661, 384_400 * KM, 7.342e22, listOf("Gravity" to "1/6 of Earth's"), 0xD8D8D8,
        )
        earth.children.add(moon); all.add(moon)
        val mars = planet("mars", "Mars", Kind.PLANET, 3389.5, Astro.MARS, 317.68, 52.89, 176.630, 350.89198226,
            Look("textures/mars.jpg", style = 0, colorA = 0xB5562E, colorB = 0x8A3D20, colorC = 0xD9A07A,
                atmosphere = 0xE8A07A, atmosphereStrength = 0.6f, heights = "heights/mars.bin"),
            -1.52, 6.417e23, 0xE07A4A, listOf("Highest point" to "Olympus Mons, about 22 km"))
        val jupiter = planet("jupiter", "Jupiter", Kind.PLANET, 69911.0, Astro.JUPITER, 268.06, 64.50, 284.95, 870.536,
            Look("textures/jupiter.jpg", style = 1, colorA = 0xD9B48A, colorB = 0xA77D58, colorC = 0xF0E0C8,
                atmosphere = 0xF5DDB8, atmosphereStrength = 0.5f),
            -9.40, 1.898e27, 0xE8C9A0, listOf("Type" to "Gas giant", "Great Red Spot" to "Storm wider than Earth"))
        val saturn = planet("saturn", "Saturn", Kind.PLANET, 58232.0, Astro.SATURN, 40.59, 83.54, 38.90, 810.7939024,
            Look("textures/saturn.jpg", style = 1, colorA = 0xE3CC92, colorB = 0xC4A86A, colorC = 0xF2E6C4,
                atmosphere = 0xF5E6B8, atmosphereStrength = 0.4f,
                ringInner = 74_500 * KM, ringOuter = 140_220 * KM, ringTexture = "textures/saturn_ring.jpg"),
            -8.88, 5.683e26, 0xEAD9A8, listOf("Rings" to "Ice and rock, mostly < 1 km thick"))
        val uranus = planet("uranus", "Uranus", Kind.PLANET, 25362.0, Astro.URANUS, 257.31, -15.18, 203.81, -501.1600928,
            Look("textures/uranus.jpg", style = 2, colorA = 0x9FD8E0, colorB = 0x86C4D0, colorC = 0xC0EAF0,
                atmosphere = 0xA8E8F0, atmosphereStrength = 0.6f),
            -7.19, 8.681e25, 0xA9E1EA, listOf("Axial tilt" to "98°, it rolls along its orbit"))
        val neptune = planet("neptune", "Neptune", Kind.PLANET, 24622.0, Astro.NEPTUNE, 299.36, 43.46, 249.978, 541.1397757,
            Look("textures/neptune.jpg", style = 2, colorA = 0x3D5FD0, colorB = 0x2C48A8, colorC = 0x7FA0F0,
                atmosphere = 0x6A8CFF, atmosphereStrength = 0.6f),
            -6.87, 1.024e26, 0x5B7FE8, listOf("Winds" to "Up to 2,100 km/h"))
        val pluto = planet("pluto", "Pluto", Kind.DWARF_PLANET, 1188.3, Astro.PLUTO, 132.99, -6.16, 302.695, 56.3625225,
            Look("textures/pluto.jpg", style = 2, colorA = 0xC8A88A, colorB = 0x8E6E58, colorC = 0xEADCC8),
            -0.7, 1.303e22, 0xD8C0A8, listOf("Heart" to "Tombaugh Regio, nitrogen ice plain"))

        // Major moons: real sizes, distances and periods; circular orbits in the planet's equator plane.
        fun moonOf(
            parent: SolarBody, id: String, name: String, rKm: Double, aKm: Double, periodDays: Double,
            colorA: Int, colorB: Int, style: Int = 0, h: Double = 5.0, atmo: Int = 0, atmoS: Float = 0f,
            facts: List<Pair<String, String>> = emptyList(),
        ) {
            val pole = Vec3d.fromRaDec(parent.poleRa, parent.poleDec)
            val ex = Vec3d.perpendicular(pole)
            val ey = pole.cross(ex)
            val phase = Rng(Rng.hashString(id)).nextDouble() * 2 * Math.PI
            val a = aKm * KM
            val fn = { jd: Double ->
                val th = phase + 2 * Math.PI * (jd - Astro.J2000) / periodDays
                ex * (a * cos(th)) + ey * (a * sin(th))
            }
            val m = SolarBody(
                "sol:$id", name, Kind.MOON, rKm * KM, parent, fn, parent.poleRa, parent.poleDec, 0.0, 0.0, true,
                Look(style = style, colorA = colorA, colorB = colorB, colorC = colorA, atmosphere = atmo, atmosphereStrength = atmoS),
                h, periodDays, a, 0.0, facts + ("Position" to "Approximate (circular orbit)"), colorA,
            )
            parent.children.add(m); all.add(m)
        }
        moonOf(mars, "phobos", "Phobos", 11.1, 9376.0, 0.31891, 0x7A6E62, 0x5A5048, h = 11.8)
        moonOf(mars, "deimos", "Deimos", 6.2, 23463.0, 1.26244, 0x8A7E70, 0x6A6056, h = 12.9)
        moonOf(jupiter, "io", "Io", 1821.6, 421_700.0, 1.769138, 0xE8D25A, 0xC08A30, style = 3, h = -1.68,
            facts = listOf("Volcanoes" to "Most volcanically active world known"))
        moonOf(jupiter, "europa", "Europa", 1560.8, 671_034.0, 3.551181, 0xE0D4C0, 0xA88A6A, style = 2, h = -1.41,
            facts = listOf("Ocean" to "Salt water under the ice"))
        moonOf(jupiter, "ganymede", "Ganymede", 2634.1, 1_070_412.0, 7.154553, 0x9A8E80, 0x6E6458, h = -2.09,
            facts = listOf("Size" to "Largest moon in the solar system"))
        moonOf(jupiter, "callisto", "Callisto", 2410.3, 1_882_709.0, 16.689018, 0x6E6458, 0x4A4238, h = -1.05)
        moonOf(saturn, "mimas", "Mimas", 198.2, 185_539.0, 0.942422, 0xC8C8C8, 0xA0A0A0, h = 3.3)
        moonOf(saturn, "enceladus", "Enceladus", 252.1, 237_948.0, 1.370218, 0xF4F8FF, 0xD8E0EA, style = 2, h = 2.2,
            facts = listOf("Geysers" to "Water jets from the south pole"))
        moonOf(saturn, "tethys", "Tethys", 531.1, 294_619.0, 1.887802, 0xE8E8E8, 0xC0C0C0, h = 0.7)
        moonOf(saturn, "dione", "Dione", 561.4, 377_396.0, 2.736915, 0xD8D8D8, 0xB0B0B0, h = 0.8)
        moonOf(saturn, "rhea", "Rhea", 763.8, 527_108.0, 4.518212, 0xD0D0D0, 0xA8A8A8, h = 0.1)
        moonOf(saturn, "titan", "Titan", 2574.7, 1_221_870.0, 15.945, 0xD8A050, 0xB07830, style = 1, h = -1.28,
            atmo = 0xE8A848, atmoS = 1.3f, facts = listOf("Atmosphere" to "Thick nitrogen haze, methane lakes"))
        moonOf(saturn, "iapetus", "Iapetus", 734.5, 3_560_820.0, 79.3215, 0x9A8A70, 0x3A3020, h = 1.5)
        moonOf(uranus, "miranda", "Miranda", 235.8, 129_390.0, 1.413479, 0xB8B8B8, 0x909090, h = 3.6)
        moonOf(uranus, "ariel", "Ariel", 578.9, 191_020.0, 2.520379, 0xC8C8C8, 0xA0A0A0, h = 1.7)
        moonOf(uranus, "umbriel", "Umbriel", 584.7, 266_000.0, 4.144177, 0x7A7A7A, 0x5A5A5A, h = 2.1)
        moonOf(uranus, "titania", "Titania", 788.4, 435_910.0, 8.705872, 0xB0A8A0, 0x8A8278, h = 1.0)
        moonOf(uranus, "oberon", "Oberon", 761.4, 583_520.0, 13.463239, 0xA09890, 0x7A726A, h = 1.2)
        moonOf(neptune, "triton", "Triton", 1353.4, 354_759.0, -5.876854, 0xE8D8D0, 0xC0A8A0, style = 2, h = -1.2,
            facts = listOf("Orbit" to "Backwards (retrograde): probably a captured world"))
        moonOf(pluto, "charon", "Charon", 606.0, 19_591.0, 6.387230, 0xA8A098, 0x807870, h = 1.0)
        return all
    }
}
