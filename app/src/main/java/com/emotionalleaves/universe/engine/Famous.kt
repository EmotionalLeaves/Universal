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
        region("bootes", "Boötes Void", Kind.VOID, 222.5, 46.0, 215 * Units.MPC, 50 * Units.MPC, "The Great Nothing",
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
        // More large structures, to visit and to show on their own ("Show only this"). Each is a
        // sphere around the structure's centre: real ones have ragged edges, so sizes are approximate.
        val M = Units.MPC
        fun z(v: Double) = comovingMpc(v) * M
        region("virgosc", "Virgo Supercluster", Kind.SUPERCLUSTER, 187.7, 12.4, 16.5 * M, 17 * M, "The Local Supercluster",
            "The flattened swarm of about 100 galaxy groups around the Virgo Cluster, with our Local Group near its edge. It is one lobe of the far larger Laniakea.",
            listOf("virgo supercluster", "local supercluster"))
        region("hydracen", "Hydra–Centaurus Supercluster", Kind.SUPERCLUSTER, 178.0, -35.0, 52 * M, 22 * M, "Our nearest neighbouring supercluster",
            "Two rich lobes around the Hydra and Centaurus clusters, lying towards the Great Attractor. Part of Laniakea.",
            listOf("hydra centaurus supercluster", "hydra supercluster", "centaurus supercluster"))
        region("perseuspisces", "Perseus–Pisces Supercluster", Kind.SUPERCLUSTER, 28.0, 36.0, 72 * M, 45 * M, "A long chain of clusters",
            "One of the largest structures in the nearby universe: a filament of galaxy clusters stretching across some 40 degrees of sky, ending at the Perseus Cluster.",
            listOf("perseus pisces supercluster", "perseus-pisces"))
        region("comasc", "Coma Supercluster", Kind.SUPERCLUSTER, 186.0, 24.0, 95 * M, 16 * M, "Coma and Leo clusters",
            "A small, round supercluster holding the Coma and Leo clusters, at the heart of the CfA2 Great Wall. The first supercluster where voids were noticed around it.",
            listOf("coma supercluster"))
        region("shapley", "Shapley Supercluster", Kind.SUPERCLUSTER, 201.98, -31.5, z(0.048), 35 * M, "The biggest concentration of galaxies nearby",
            "The most massive collection of galaxy clusters within a billion light years: 25 or so rich clusters packed together. Its gravity helps pull our whole region of space towards it.",
            listOf("shapley supercluster", "shapley concentration"))
        region("herculessc", "Hercules Superclusters", Kind.SUPERCLUSTER, 241.0, 17.5, z(0.036), 30 * M, "Twin superclusters beside the Great Wall",
            "Two neighbouring superclusters around the Hercules Cluster, joined to the Coma Supercluster by the CfA2 Great Wall.",
            listOf("hercules supercluster", "hercules superclusters"))
        region("coronabor", "Corona Borealis Supercluster", Kind.SUPERCLUSTER, 232.0, 29.0, z(0.072), 30 * M, "A supercluster that may be collapsing",
            "A tight knot of rich galaxy clusters about a billion light years away, dense enough that its core may be falling together under its own gravity.",
            listOf("corona borealis supercluster", "crb supercluster"))
        region("horologium", "Horologium–Reticulum Supercluster", Kind.SUPERCLUSTER, 49.75, -50.0, z(0.063), 75 * M, "A huge southern supercluster",
            "One of the largest superclusters known nearby: about 5,000 galaxy groups spread over 550 million light years.",
            listOf("horologium reticulum supercluster", "horologium supercluster"))
        region("pavoindus", "Pavo–Indus Supercluster", Kind.SUPERCLUSTER, 312.0, -57.0, 65 * M, 25 * M, "A southern arm of Laniakea",
            "A wall of galaxy clusters in the southern sky, flowing with us towards the Great Attractor.",
            listOf("pavo indus supercluster", "pavo-indus"))
        region("ophiuchussc", "Ophiuchus Supercluster", Kind.SUPERCLUSTER, 257.5, -22.5, z(0.028), 12 * M, "Hidden behind the Milky Way",
            "A supercluster lying almost behind the centre of our galaxy, found in 2006. It forms the far wall of the Ophiuchus Void.",
            listOf("ophiuchus supercluster"))
        region("saraswati", "Saraswati Supercluster", Kind.SUPERCLUSTER, 351.25, 0.0, z(0.28), 100 * M, "A giant wall 4 billion light years away",
            "A supercluster about 650 million light years long with the mass of 20 million billion Suns, found in 2017 in Sloan survey data.",
            listOf("saraswati supercluster"))
        region("bosswall", "BOSS Great Wall", Kind.SUPERCLUSTER, 163.0, 52.0, z(0.47), 150 * M, "A billion-light-year wall of galaxies",
            "A complex of four superclusters found in 2016, about a billion light years across: among the largest structures ever mapped.",
            listOf("boss great wall", "bgw"))
        region("localvoid", "Local Void", Kind.VOID, 279.5, 18.0, 23 * M, 22 * M, "The emptiness next door",
            "A vast empty region starting right at the edge of our Local Group. Its lack of pull lets our galaxy drift away from it at over 200 km/s.",
            listOf("local void", "tully void"))
        region("giantvoid", "Giant Void", Kind.VOID, 195.25, 38.75, z(0.116), 170 * M, "One of the biggest voids known",
            "A region more than a billion light years across in Canes Venatici with far fewer galaxies than normal.",
            listOf("giant void", "canes venatici supervoid", "aen-1"))
        region("eridanus", "Eridanus Supervoid", Kind.VOID, 48.77, -19.58, z(0.22), 300 * M, "Behind the CMB Cold Spot (debated)",
            "A huge thinly populated region in the direction of the Cold Spot in the cosmic microwave background. Whether it is big and empty enough to cause the Cold Spot is still argued over.",
            listOf("eridanus supervoid", "cold spot void", "cmb cold spot"))
        region("kbcvoid", "KBC Void", Kind.VOID, 0.0, 0.0, 0.0, 300 * M, "The 'Local Hole' we may live in (debated)",
            "Galaxy counts suggest our whole neighbourhood, out to about a billion light years, is somewhat emptier than average, with the Milky Way near the middle. Named after Keenan, Barger and Cowie.",
            listOf("kbc void", "local hole", "keenan barger cowie"))
        region("localgroup", "Local Group", Kind.GALAXY_CLUSTER, 10.68, 41.27, 0.4 * M, 1.5 * M, "Our own group of galaxies",
            "The Milky Way, Andromeda, Triangulum and around 80 dwarf galaxies, bound together by gravity in a volume about 10 million light years across.",
            listOf("local group"))
        region("m81group", "M81 Group", Kind.GALAXY_CLUSTER, 148.9, 69.07, 3.6 * M, 0.8 * M, "A neighbouring group",
            "A nearby group of about 34 galaxies around the spiral M81 and the starburst galaxy M82.",
            listOf("m81 group", "ursa major group m81"))
        region("cenagroup", "Centaurus A/M83 Group", Kind.GALAXY_CLUSTER, 201.4, -40.0, 4.2 * M, 1.4 * M, "A neighbouring group",
            "Two nearby clumps of galaxies around the radio galaxy Centaurus A and the spiral M83.",
            listOf("centaurus a group", "m83 group", "cen a group"))
        region("sculptorgroup", "Sculptor Group", Kind.GALAXY_CLUSTER, 11.9, -25.3, 3.5 * M, 1.0 * M, "One of the nearest groups",
            "A loose string of galaxies near the south galactic pole, led by the starburst spiral NGC 253.",
            listOf("sculptor group", "south polar group"))
        region("virgocluster", "Virgo Cluster", Kind.GALAXY_CLUSTER, 187.70, 12.39, 16.5 * M, 2.2 * M, "The nearest big cluster",
            "About 1,500 galaxies around the giants M87, M86 and M49: the heart of our Local Supercluster.",
            listOf("virgo cluster"))
        region("fornaxcluster", "Fornax Cluster", Kind.GALAXY_CLUSTER, 54.62, -35.45, 19.5 * M, 1.2 * M, "The second nearest rich cluster",
            "A compact cluster of several hundred galaxies around the elliptical NGC 1399.",
            listOf("fornax cluster"))
        region("centauruscluster", "Centaurus Cluster", Kind.GALAXY_CLUSTER, 192.2, -41.31, z(0.0114), 2.0 * M, "Abell 3526",
            "A cluster of hundreds of galaxies around NGC 4696, in the Hydra–Centaurus Supercluster.",
            listOf("centaurus cluster", "abell 3526"))
        region("hydracluster", "Hydra Cluster", Kind.GALAXY_CLUSTER, 159.17, -27.52, z(0.0126), 2.0 * M, "Abell 1060",
            "A rich cluster of over 150 bright galaxies, 10 million light years across.",
            listOf("hydra cluster", "abell 1060"))
        region("perseuscluster", "Perseus Cluster", Kind.GALAXY_CLUSTER, 49.95, 41.51, z(0.0179), 2.5 * M, "Abell 426",
            "One of the most massive objects nearby: thousands of galaxies in a cloud of gas at tens of millions of degrees, the brightest cluster in X-rays.",
            listOf("perseus cluster", "abell 426"))
        region("comacluster", "Coma Cluster", Kind.GALAXY_CLUSTER, 194.95, 27.98, z(0.0231), 3.0 * M, "Abell 1656",
            "Over 1,000 galaxies, mostly ellipticals. Fritz Zwicky's study of it in 1933 was the first sign of dark matter.",
            listOf("coma cluster", "abell 1656"))
        region("herculescluster", "Hercules Cluster", Kind.GALAXY_CLUSTER, 241.3, 17.75, z(0.0366), 2.0 * M, "Abell 2151",
            "A cluster rich in spiral and interacting galaxies, about 500 million light years away.",
            listOf("hercules cluster", "abell 2151"))
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
