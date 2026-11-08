package com.emotionalleaves.universe.engine

import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Record holders and famous objects people look for by name, many of them missing from the big
 * catalogues (TON 618 is not in the quasar list we ship): real positions and sizes from the
 * literature. Searchable (with their nicknames), drawn up close and tappable.
 */
class FamousBody(
    val slug: String,
    override val name: String,
    override val kind: Kind,
    private val pos: UPos,
    override val radius: Double,
    private val sub: String,
    val about: String,
    private val extra: List<Pair<String, String>>,
    val aliases: List<String>,
    override val color: Int,
    /** How far away its label still shows (metres). */
    val labelRange: Double,
) : Body() {
    override val id = "fam:$slug"
    override val source = Source.REAL
    override val subtitle get() = sub
    override fun position(jd: Double) = pos
    override fun facts(jd: Double) = extra
    override fun viewDistance() = when (kind) {
        Kind.VOID, Kind.SUPERCLUSTER -> radius * 2.6
        Kind.SPACECRAFT -> 60.0
        Kind.BLACK_HOLE, Kind.QUASAR -> radius * 6.0
        else -> radius * 3.2
    }
}

object Famous {
    private const val MSUN_RS = 2953.25 // Schwarzschild radius of one solar mass, metres
    private val R_SUN = Units.R_SUN

    /** Comoving distance (Mpc) for redshift z: flat ΛCDM, H0 = 70, Ωm = 0.3. */
    fun comovingMpc(z: Double): Double {
        val n = 400
        var s = 0.0
        for (k in 0 until n) {
            val zz = (k + 0.5) * z / n
            s += 1.0 / sqrt(0.3 * (1 + zz).pow(3) + 0.7)
        }
        return 4282.7 * s * z / n
    }

    private fun at(ra: Double, dec: Double, metres: Double) = UPos.ZERO + Vec3d.fromRaDec(ra, dec) * metres
    private fun pc(x: Double) = x * Units.PC
    private fun ly(m: Double) = Units.formatDistance(m)
    private fun f(v: Double, d: Int = 1) = String.format(Locale.US, "%,.${d}f", v)

    val all: List<FamousBody> by lazy { build() }
    private val byId by lazy { all.associateBy { it.id } }

    fun find(id: String) = byId[id]

    private fun build(): List<FamousBody> {
        val out = ArrayList<FamousBody>()
        fun hole(slug: String, name: String, ra: Double, dec: Double, dist: Double, msun: Double, sub: String, about: String,
                 aliases: List<String>, more: List<Pair<String, String>> = emptyList(), kind: Kind = Kind.BLACK_HOLE) {
            val rs = msun * MSUN_RS
            out.add(FamousBody(slug, name, kind, at(ra, dec, dist), rs, sub, about,
                listOf("Mass" to "${Units.bigCount(msun)} Suns", "Event horizon" to "${ly(rs * 2)} across", "Distance" to ly(dist)) + more,
                aliases, if (kind == Kind.QUASAR) 0xFFE2B8 else 0xC8A8FF, dist * 4))
        }
        fun star(slug: String, name: String, ra: Double, dec: Double, dist: Double, rSun: Double, teff: Double, sub: String, about: String,
                 aliases: List<String>, more: List<Pair<String, String>> = emptyList()) {
            val col = when { teff < 3700 -> 0xFFB27A; teff < 5200 -> 0xFFD2A0; teff < 7000 -> 0xFFF4E0; else -> 0xCADBFF }
            out.add(FamousBody(slug, name, Kind.STAR, at(ra, dec, dist), rSun * R_SUN, sub, about,
                listOf("Width" to "${f(rSun * 2, 0)} × the Sun", "Surface" to "${f(teff, 0)} K", "Distance" to ly(dist)) + more,
                aliases, col, pc(3000.0)))
        }
        fun region(slug: String, name: String, kind: Kind, ra: Double, dec: Double, dist: Double, radius: Double, sub: String, about: String,
                   aliases: List<String>, more: List<Pair<String, String>> = emptyList()) {
            out.add(FamousBody(slug, name, kind, at(ra, dec, dist), radius, sub, about,
                listOf("Size" to "${ly(radius * 2)} across", "Distance" to ly(dist)) + more, aliases,
                if (kind == Kind.VOID) 0x8FA6C8 else 0xFFD8A8, radius * 60))
        }
        fun far(slug: String, name: String, kind: Kind, ra: Double, dec: Double, z: Double, radius: Double, sub: String, about: String,
                aliases: List<String>, msun: Double = 0.0) {
            val d = comovingMpc(z) * Units.MPC
            val more = listOf("Redshift" to "z = ${f(z, 2)}", "Seen as it was" to ExoticCatalog.lookback(z), "Distance now" to ly(d))
            if (msun > 0) hole(slug, name, ra, dec, d, msun, sub, about, aliases, more.drop(1), kind)
            else out.add(FamousBody(slug, name, kind, at(ra, dec, d), radius, sub, about, more, aliases, 0xFFE8C8, d * 4))
        }

        far("ton618", "TON 618", Kind.QUASAR, 187.1040, 31.4772, 2.219, 0.0, "Quasar · one of the biggest black holes known",
            "TON 618 is a quasar powered by one of the most massive black holes ever measured, about 40 billion times the Sun's mass. Its event horizon is wider than our whole Solar System out to Pluto many times over, and the gas swirling into it outshines 140 trillion Suns.",
            listOf("ton 618", "ton618", "tonantzintla 618", "biggest black hole", "largest black hole"), msun = 4.07e10)
        far("phoenixa", "Phoenix A", Kind.BLACK_HOLE, 356.1833, -42.7233, 0.597, 0.0, "Black hole at the heart of the Phoenix Cluster",
            "The central black hole of the Phoenix Cluster, estimated at around 100 billion Suns (a very uncertain, indirect estimate). If right, it is among the largest black holes in the universe.",
            listOf("phoenix a", "phoenix a*"), msun = 1.0e11)
        hole("sgra", "Sagittarius A*", 266.41683, -29.00781, pc(8178.0), 4.3e6, "The black hole at the centre of the Milky Way",
            "The supermassive black hole at the centre of our galaxy, four million times the Sun's mass. Stars race around it at thousands of kilometres a second; the Event Horizon Telescope photographed its shadow in 2022.",
            listOf("sgr a*", "sagittarius a", "sgr a", "sag a*", "sag a star", "sgr a star", "milky way black hole", "galactic center", "galactic centre"))
        hole("m87bh", "M87*", 187.70593, 12.39112, 16.8 * Units.MPC, 6.5e9, "Black hole in the galaxy M87",
            "The first black hole ever photographed (Event Horizon Telescope, 2019): 6.5 billion Suns at the core of the giant elliptical galaxy M87, launching a jet of particles thousands of light years long.",
            listOf("m87 black hole", "powehi", "m87*"))
        hole("cygx1", "Cygnus X-1", 299.5903, 35.2016, pc(2220.0), 21.2, "Black hole eating a blue supergiant",
            "The first object widely accepted to be a black hole (1964): 21 Suns' worth, pulling gas off its giant companion star every 5.6 days and shining brightly in X-rays.",
            listOf("cyg x-1", "cygnus x1"))
        hole("gaiabh1", "Gaia BH1", 262.1712, -0.5809, pc(480.0), 9.62, "The nearest known black hole",
            "A quiet black hole about 1,560 light years away, found in 2022 because a Sun-like star circles it every 186 days.",
            listOf("gaia bh1", "nearest black hole", "closest black hole"))
        star("uyscuti", "UY Scuti", 276.3955, -12.4622, pc(2900.0), 909.0, 3365.0, "Red supergiant · one of the largest stars",
            "One of the largest known stars: put it where the Sun is and its surface would reach past Mars' orbit. It is so tenuous that its average density is far less than air.",
            listOf("uy sct", "biggest star", "largest star"))
        star("st218", "Stephenson 2-18", 277.2208, -6.0317, pc(5800.0), 2150.0, 3200.0, "Red supergiant · perhaps the largest star known",
            "A red supergiant in the cluster Stephenson 2. Estimates make it over 2,000 times the Sun's width, which would swallow Saturn's orbit, though the size is uncertain.",
            listOf("stephenson 2 18", "st2-18", "stephenson 2-18", "st2 18"))
        star("vycma", "VY Canis Majoris", 110.7429, -25.7675, pc(1170.0), 1420.0, 3490.0, "Red hypergiant",
            "A dying red hypergiant shedding huge clouds of gas and dust: about 1,400 times the Sun's width and losing a Sun's worth of mass every few thousand years.",
            listOf("vy cma", "vy canis major"))
        star("wohg64", "WOH G64", 73.7938, -68.3417, pc(49500.0), 1540.0, 3400.0, "Red supergiant in the Large Magellanic Cloud",
            "A giant star in a neighbouring galaxy, wrapped in a cocoon of dust. Telescopes took the first close-up of a star in another galaxy of it in 2024.",
            listOf("woh g 64"))
        far("earendel", "Earendel", Kind.STAR, 24.3546, -8.4567, 6.2, 50 * R_SUN, "The most distant single star seen",
            "A star seen as it was 12.8 billion years ago, magnified thousands of times by a galaxy cluster's gravity. It is probably a hot star at least 50 times the Sun's mass.",
            listOf("whl0137-ls", "most distant star", "farthest star"))
        far("gnz11", "GN-z11", Kind.GALAXY, 189.1060, 62.2420, 10.6, pc(200.0), "Very early galaxy",
            "A galaxy seen just 430 million years after the Big Bang, with a surprisingly active black hole at its heart (JWST).",
            listOf("gn z11", "gnz11"))
        far("jadesz14", "JADES-GS-z14-0", Kind.GALAXY, 53.0829, -27.8556, 14.32, pc(260.0), "Most distant galaxy confirmed (2024)",
            "Seen just 290 million years after the Big Bang, about 1,600 light years across and already bright: one of the earliest galaxies ever found (JWST).",
            listOf("jades gs z14 0", "jades-gs-z14", "most distant galaxy", "farthest galaxy"))
        region("bootes", "Boötes Void", Kind.VOID, 218.0, 46.0, 210 * Units.MPC, 62 * Units.MPC, "The Great Nothing",
            "A huge, nearly empty sphere of space about 330 million light years across. It holds only around 60 galaxies where thousands would be expected.",
            listOf("bootes void", "great nothing", "great void"))
        region("laniakea", "Laniakea Supercluster", Kind.SUPERCLUSTER, 243.6, -60.9, 77 * Units.MPC, 80 * Units.MPC, "Our home supercluster",
            "Our cosmic home: about 100,000 galaxies, the Milky Way among them, all flowing towards the same region of space. Its name is Hawaiian for 'immeasurable heaven'.",
            listOf("laniakea", "home supercluster"))
        region("greatattractor", "Great Attractor", Kind.SUPERCLUSTER, 243.6, -60.9, 67 * Units.MPC, 8 * Units.MPC, "Gravity centre of Laniakea",
            "A concentration of mass, hidden behind the Milky Way's disk, that pulls the Milky Way and thousands of other galaxies towards it at hundreds of km/s.",
            listOf("great attractor", "norma cluster"))
        region("sloanwall", "Sloan Great Wall", Kind.SUPERCLUSTER, 195.0, 2.0, 310 * Units.MPC, 210 * Units.MPC, "Wall of galaxies",
            "One of the largest known structures: a sheet of galaxies about 1.4 billion light years long.",
            listOf("sloan great wall", "great wall"))
        region("herculeswall", "Hercules–Corona Borealis Great Wall", Kind.SUPERCLUSTER, 240.0, 32.0, comovingMpc(1.8) * Units.MPC, 1500 * Units.MPC,
            "Largest structure claimed in the universe",
            "A vast cluster of gamma-ray bursts suggests a structure about 10 billion light years long, so big it challenges the idea that the universe is smooth on large scales (still debated).",
            listOf("hercules corona borealis great wall", "her-crb gw", "largest structure"))
        region("bullet", "Bullet Cluster", Kind.GALAXY_CLUSTER, 104.65, -55.95, comovingMpc(0.296) * Units.MPC, 1.2 * Units.MPC,
            "Two colliding galaxy clusters",
            "Two clusters that smashed through each other: their hot gas slowed down, but most of their mass did not. One of the clearest signs of dark matter.",
            listOf("bullet cluster", "1e 0657-56"))
        region("elgordo", "El Gordo", Kind.GALAXY_CLUSTER, 15.72, -49.25, comovingMpc(0.87) * Units.MPC, 2.0 * Units.MPC,
            "The 'Fat One', a colossal colliding cluster",
            "One of the most massive galaxy clusters known from the early universe: two clusters merging, together about 2 quadrillion Suns.",
            listOf("el gordo", "act-cl j0102-4915"))
        // Spacecraft: directions and distances for late 2026 (approximate).
        out.add(FamousBody("voyager1", "Voyager 1", Kind.SPACECRAFT, at(258.5, 12.1, 171 * Units.AU), 2.0, "The farthest human-made object",
            "Launched in 1977, it flew past Jupiter and Saturn and crossed into interstellar space in 2012. It still radios home; the signal takes over 23 hours to arrive.",
            listOf("Distance" to "about 171 AU (late 2026)", "Speed" to "17 km/s"), listOf("voyager", "voyager 1", "farthest spacecraft"), 0xDDE6FF, 3000 * Units.AU))
        out.add(FamousBody("voyager2", "Voyager 2", Kind.SPACECRAFT, at(300.4, -59.6, 143 * Units.AU), 2.0, "The only probe to visit Uranus and Neptune",
            "Launched in 1977, it is the only spacecraft to have visited Uranus and Neptune, and entered interstellar space in 2018.",
            listOf("Distance" to "about 143 AU (late 2026)", "Speed" to "15 km/s"), listOf("voyager 2"), 0xDDE6FF, 3000 * Units.AU))
        return out
    }
}
