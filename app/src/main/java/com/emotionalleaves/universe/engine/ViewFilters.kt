package com.emotionalleaves.universe.engine

/**
 * What is shown in the universe. Each filter hides one kind of thing from view (drawing, labels and
 * taps); nothing is unloaded, so switching back is instant. A few are the older display settings.
 */
enum class ViewFilter(val title: String, val description: String, val group: String, val generated: Boolean = false) {
    STARS("Stars", "Real stars: Hipparcos, AT-HYG and Gaia", "Stars & Milky Way"),
    CONSTELLATIONS("Constellation lines", "Stick figures between real stars", "Stars & Milky Way"),
    GEN_STARS("Generated stars", "Stars made up where catalogues end", "Stars & Milky Way", generated = true),
    GALAXY_GLOW("Milky Way glow", "The light of the galaxy around you, and galaxies seen up close", "Stars & Milky Way"),
    NEBULAE("Nebulae & star clusters", "Real nebulae, open and globular clusters", "Stars & Milky Way"),
    PULSARS("Pulsars", "Real pulsars (ATNF catalogue)", "Stars & Milky Way"),
    REMNANTS("Black holes & neutron stars", "Generated ones around you", "Stars & Milky Way", generated = true),

    PLANETS("Sun, planets & moons", "Our solar system", "Planets & small bodies"),
    SYSTEMS("Other star systems", "Planets of other stars: real exoplanets and generated ones", "Planets & small bodies"),
    ORBITS("Orbit lines", "Paths of planets, moons and asteroids", "Planets & small bodies"),
    ASTEROIDS("Asteroids & comets", "Every known asteroid and comet", "Planets & small bodies"),
    BELTS("Generated belts & rocks", "Asteroid belts of other stars, rocks near you", "Planets & small bodies", generated = true),

    REAL_GALAXIES("Real galaxies", "Catalogued galaxies: GLADE+, DESI, SDSS", "Galaxies & beyond"),
    GEN_GALAXIES("Generated galaxies", "Galaxies made up where no survey has looked", "Galaxies & beyond", generated = true),
    CLUSTERS("Galaxy clusters", "Abell clusters of galaxies", "Galaxies & beyond"),
    QUASARS("Quasars", "Million Quasars catalogue", "Galaxies & beyond"),
    HAZE("Distant glow", "The soft light of galaxies too far to see one by one", "Galaxies & beyond"),
    CMB("Cosmic microwave background", "The Planck map at the edge of the observable universe", "Galaxies & beyond"),

    FAMOUS("Famous objects & records", "Biggest, farthest, most massive: black holes, giant stars, voids, spacecraft", "Labels & extras"),
    ONLINE("Online search results", "Objects you found through SIMBAD, NED or JPL", "Labels & extras"),
    LABELS("Labels", "Names next to things", "Labels & extras");

    companion object {
        /** Which filter a body falls under (for labels and taps), or null when it is always shown. */
        fun of(b: Body): ViewFilter? = when (b.kind) {
            Kind.STAR -> if (b.source == Source.GENERATED) GEN_STARS else STARS
            Kind.PLANET, Kind.DWARF_PLANET, Kind.MOON -> null
            Kind.GALAXY -> if (b.source == Source.GENERATED) GEN_GALAXIES else REAL_GALAXIES
            Kind.NEBULA, Kind.CLUSTER -> NEBULAE
            Kind.ASTEROID, Kind.COMET -> if (b.source == Source.GENERATED) BELTS else ASTEROIDS
            Kind.PULSAR -> PULSARS
            Kind.QUASAR -> QUASARS
            Kind.GALAXY_CLUSTER -> CLUSTERS
            Kind.BLACK_HOLE, Kind.NEUTRON_STAR, Kind.ROGUE_PLANET -> if (b.source == Source.GENERATED) REMNANTS else null
            else -> null
        }
    }
}

/** Shown/hidden state of every [ViewFilter], saved in [Settings]. */
class ViewFilters(private val s: Settings) {
    fun shown(f: ViewFilter): Boolean = when (f) {
        ViewFilter.ORBITS -> s.showOrbits
        ViewFilter.LABELS -> s.showLabels
        ViewFilter.CONSTELLATIONS -> s.showConstellations
        else -> f.name !in s.hiddenFilters
    }

    fun set(f: ViewFilter, on: Boolean) {
        when (f) {
            ViewFilter.ORBITS -> s.showOrbits = on
            ViewFilter.LABELS -> s.showLabels = on
            ViewFilter.CONSTELLATIONS -> s.showConstellations = on
            else -> s.hiddenFilters = if (on) s.hiddenFilters - f.name else s.hiddenFilters + f.name
        }
        version++
    }

    /** A body's labels and taps are shown (its kind isn't filtered out). */
    fun shows(b: Body): Boolean = ViewFilter.of(b)?.let { shown(it) } ?: true

    /** Bumped on every change, so layers can rebuild what they cache. */
    @Volatile var version = 0
        private set

    fun showAll() { for (f in ViewFilter.values()) if (f != ViewFilter.CONSTELLATIONS) set(f, true) }
    fun realOnly() { for (f in ViewFilter.values()) if (f.generated) set(f, false) else if (f != ViewFilter.CONSTELLATIONS) set(f, true) }
    fun hiddenCount() = ViewFilter.values().count { it != ViewFilter.CONSTELLATIONS && !shown(it) }
}
