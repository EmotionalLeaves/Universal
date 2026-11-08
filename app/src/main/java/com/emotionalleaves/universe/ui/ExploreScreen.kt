package com.emotionalleaves.universe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emotionalleaves.universe.engine.Engine

/** One place in the Explore list: the label shown, the name searched for, and a short hint. */
private data class Place(val label: String, val query: String, val hint: String)

private class Category(val glyph: G, val kind: String, val title: String, val blurb: String, val places: List<Place>)

private fun p(label: String, hint: String, query: String = label) = Place(label, query, hint)

private val CATEGORIES = listOf(
    Category(
        G.SUN, "star", "Solar system", "Our Sun, its planets and their moons.",
        listOf(
            p("Sun", "Our star"), p("Mercury", "Smallest planet"), p("Venus", "Hottest planet"),
            p("Earth", "Home"), p("Moon", "Our natural satellite"), p("Mars", "The red planet"),
            p("Jupiter", "Largest planet"), p("Io", "Volcanic moon of Jupiter"), p("Europa", "Icy moon of Jupiter"),
            p("Saturn", "Rings"), p("Titan", "Hazy moon of Saturn"), p("Uranus", "Tilted on its side"),
            p("Neptune", "Farthest planet"), p("Pluto", "Dwarf planet"),
        ),
    ),
    Category(
        G.ASTEROID, "asteroid", "Asteroids and comets", "Real orbits from NASA JPL for tens of thousands of them.",
        listOf(
            p("Ceres", "Largest asteroid, a dwarf planet", "1 Ceres"), p("Vesta", "Giant asteroid with a huge crater", "4 Vesta"),
            p("Pallas", "Third-largest asteroid", "2 Pallas"), p("Eros", "First asteroid a spacecraft landed on", "433 Eros"),
            p("Psyche", "A metal-rich asteroid, NASA mission target", "16 Psyche"), p("Ida", "First asteroid found with a moon", "243 Ida"),
            p("Eris", "Distant dwarf planet, almost Pluto's size", "136199 Eris"), p("Sedna", "Takes 11,000 years to orbit", "90377 Sedna"),
            p("Halley's Comet", "Returns every 76 years", "1P/Halley"), p("Comet 67P", "Visited by Rosetta", "67P/Churyumov-Gerasimenko"),
            p("Encke's Comet", "Shortest-period big comet", "2P/Encke"),
        ),
    ),
    Category(
        G.MOON, "moon", "More moons", "Every known moon of the planets, with real orbits.",
        listOf(
            p("Triton", "Neptune's moon, orbits backwards"), p("Phoebe", "Saturn's captured outer moon"),
            p("Enceladus", "Ice geysers over a hidden ocean"), p("Miranda", "Patchwork moon of Uranus"),
            p("Charon", "Pluto's giant moon"), p("Himalia", "Largest irregular moon of Jupiter"),
        ),
    ),
    Category(
        G.STAR, "star", "Famous stars", "Bright and well-known stars, all at their real positions.",
        listOf(
            p("Proxima Centauri", "Nearest star, 4.2 ly"), p("Rigil Kentaurus", "Alpha Centauri A"),
            p("Sirius", "Brightest star in our sky"), p("Barnard's Star", "Fast-moving red dwarf"),
            p("Vega", "Bright blue-white star"), p("Polaris", "The North Star"),
            p("Betelgeuse", "Red supergiant in Orion"), p("Rigel", "Blue supergiant in Orion"),
            p("Antares", "Red heart of Scorpius"), p("Canopus", "Second-brightest star"),
            p("Deneb", "Very distant, very bright"), p("Arcturus", "Orange giant"),
        ),
    ),
    Category(
        G.PLANET, "planet", "Exoplanets", "Real planets around other stars (NASA Exoplanet Archive).",
        listOf(
            p("Proxima Cen b", "Nearest exoplanet"), p("TRAPPIST-1 e", "7 Earth-size worlds"),
            p("TOI-700 d", "Earth-size, habitable zone"), p("Kepler-452 b", "\"Earth's cousin\""),
            p("Kepler-186 f", "First Earth-size habitable-zone find"), p("Kepler-22 b", "Possible ocean world"),
            p("51 Peg b", "First planet found around a Sun-like star"), p("HD 209458 b", "First transiting planet"),
            p("55 Cnc e", "Lava world"), p("HR 8799 b", "Directly photographed giant"),
            p("WASP-12 b", "Being eaten by its star"),
        ),
    ),
    Category(
        G.NEBULA, "nebula", "Nebulae and clusters", "Gas clouds and star clusters in the Milky Way (OpenNGC).",
        listOf(
            p("Orion Nebula", "Nearest big star nursery"), p("Pleiades", "Seven Sisters cluster"),
            p("Horsehead Nebula", "Dark cloud in Orion"), p("Crab Nebula", "Supernova remnant"),
            p("Ring Nebula", "Dying star's shell"), p("Helix Nebula", "\"Eye of God\""),
            p("Eagle Nebula", "Pillars of Creation"), p("Lagoon Nebula", "Bright nursery in Sagittarius"),
            p("Trifid Nebula", "Three-lobed nebula"), p("Dumbbell Nebula", "Planetary nebula"),
            p("Carina Nebula", "Huge southern nebula"), p("Omega Centauri", "Biggest globular cluster"),
            p("Hercules Cluster", "Globular cluster M13", "M13"), p("Beehive Cluster", "Open cluster M44", "M44"),
        ),
    ),
    Category(
        G.GALAXY, "galaxy", "Galaxies", "Our galaxy and its neighbours. Zoom out further for millions more.",
        listOf(
            p("Milky Way", "Our galaxy, seen from outside"),
            p("Large Magellanic Cloud", "Satellite galaxy, 160,000 ly"),
            p("Small Magellanic Cloud", "Satellite galaxy"),
            p("Andromeda Galaxy", "Nearest big galaxy, 2.5 Mly"), p("Triangulum Galaxy", "Third-largest in our group"),
            p("Whirlpool Galaxy", "Classic spiral with a companion"), p("Pinwheel Galaxy", "Face-on spiral"),
            p("Sombrero Galaxy", "Edge-on with a dust lane"), p("Cigar Galaxy", "Starburst galaxy"),
            p("Centaurus A", "Giant galaxy with a black-hole jet"),
            p("M87", "Its black hole was the first ever photographed", "Messier 87"),
        ),
    ),
    Category(
        G.BLACKHOLE, "black hole", "Strange things", "Dead stars, black holes and the brightest objects in the universe.",
        listOf(
            p("Crab Pulsar", "Spins 30 times a second", "PSR J0534+2200"), p("Vela Pulsar", "Spins 11 times a second", "PSR J0835-4510"),
            p("3C 273", "First quasar ever identified"), p("Coma Cluster", "Over 1,000 galaxies", "Abell 1656"),
            p("Perseus Cluster", "Brightest X-ray cluster", "Abell 426"),
        ),
    ),
    Category(
        G.UNIVERSE, "galaxy cluster", "The biggest scale", "The cosmic web and the edge of what we can see.",
        listOf(p("Observable universe", "Everything, out to the CMB"), p("Beyond the edge", "Speculative: the universe we can never see", "@beyond")),
    ),
)

/** Which Explore sections are open (kept while the app runs). */
object ExploreUi {
    var open by mutableStateOf(setOf("Solar system"))
}

/** Browsable list of famous places, so you don't need to know names to search for. */
@Composable
fun ExploreScreen(engine: Engine) {
    var open by ExploreUi::open
    Panel("Explore", { Nav.screen = Screen.NONE }) {
        val count = com.emotionalleaves.universe.engine.Census.total(engine.settings.showBeyond && engine.settings.showGenerated)
        Column(
            Modifier.fillMaxWidth().glass(16.dp, raised = true).clickable { Nav.screen = Screen.CENSUS }.padding(14.dp),
        ) {
            Overline("This universe holds", color = Accent)
            Text("${com.emotionalleaves.universe.core.Units.sci(count)} things", style = Type.Title.copy(color = Accent))
            Text("Every star, planet, moon and rock down to 1 m — all of them can be visited. Tap for the count.", style = Type.Caption)
        }
        Text("Famous places, sorted by type. One tap flies you there.", style = Type.BodyDim, modifier = Modifier.padding(top = 12.dp))
        Overline("Surprise me", Modifier.padding(top = 18.dp, bottom = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SurpriseTile(G.PLANET, "Rocky world", "around a real star", kindColor("planet"), Modifier.weight(1f)) { engine.surprise(rocky = true); Nav.screen = Screen.NONE }
            SurpriseTile(G.SUN, "Any planet", "gas giants too", kindColor("star"), Modifier.weight(1f)) { engine.surprise(rocky = false); Nav.screen = Screen.NONE }
            SurpriseTile(G.GALAXY, "Galaxy", "far, far away", kindColor("galaxy"), Modifier.weight(1f)) { engine.surpriseGalaxy(); Nav.screen = Screen.NONE }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SurpriseTile(G.BLACKHOLE, "Black hole", "in the Milky Way", kindColor("black hole"), Modifier.weight(1f)) { engine.surpriseRemnant(com.emotionalleaves.universe.engine.Kind.BLACK_HOLE); Nav.screen = Screen.NONE }
            SurpriseTile(G.SPARKLE, "Neutron star", "a dead star's core", kindColor("pulsar"), Modifier.weight(1f)) { engine.surpriseRemnant(com.emotionalleaves.universe.engine.Kind.NEUTRON_STAR); Nav.screen = Screen.NONE }
            SurpriseTile(G.MOON, "Rogue planet", "no sun at all", kindColor("moon"), Modifier.weight(1f)) { engine.surpriseRemnant(com.emotionalleaves.universe.engine.Kind.ROGUE_PLANET); Nav.screen = Screen.NONE }
        }
        Overline("Places", Modifier.padding(top = 22.dp, bottom = 4.dp))
        for (c in CATEGORIES) {
            val isOpen = c.title in open
            val tint = kindColor(c.kind)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { open = if (isOpen) open - c.title else open + c.title }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(38.dp).background(tint.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                    Glyph(c.glyph, size = 20.dp, color = tint)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.title, style = Type.Heading)
                    Text(c.blurb, style = Type.Caption, maxLines = 2)
                }
                Text("${c.places.size}", style = Type.Caption.copy(color = TextFaint), modifier = Modifier.padding(horizontal = 8.dp))
                Glyph(if (isOpen) G.CHEVRON_UP else G.CHEVRON_DOWN, size = 18.dp, color = TextDim)
            }
            if (isOpen) {
                Column(Modifier.fillMaxWidth().padding(bottom = 6.dp).glass(16.dp, raised = true).padding(horizontal = 14.dp, vertical = 2.dp)) {
                    for ((k, pl) in c.places.withIndex()) {
                        if (k > 0) Hairline()
                        ListRow(pl.label, pl.hint, trailing = "Fly") {
                            if (pl.query == "@beyond") engine.goBeyond() else engine.goToName(pl.query)
                            Nav.screen = Screen.NONE
                        }
                    }
                }
            }
            Hairline()
        }
    }
}

@Composable
private fun SurpriseTile(g: G, title: String, detail: String, tint: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.glass(14.dp, raised = true).clickable { onClick() }.padding(10.dp),
    ) {
        Box(Modifier.size(30.dp).background(tint.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
            Glyph(g, size = 17.dp, color = tint)
        }
        Text(title, style = Type.Body.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp), modifier = Modifier.padding(top = 8.dp), maxLines = 1)
        Text(detail, style = Type.Caption.copy(fontSize = 11.sp), maxLines = 1)
    }
}

/** How much of the universe is in the app: real catalogues, and what is generated on demand. */
@Composable
fun CensusScreen(engine: Engine) {
    val w = engine.world
    fun n(v: Number) = String.format(java.util.Locale.US, "%,d", v.toLong())
    val rs = w.realStars
    val starCount = (rs?.base?.count ?: 0) + (rs?.packs?.sumOf { it.count } ?: 0) + (rs?.exoHosts?.count ?: 0)
    val real = listOf(
        "Stars" to n(starCount),
        "Planets around other stars" to n(w.exo?.planetCount ?: 0),
        "Moons of our planets" to n(w.solar.count { it.kind == com.emotionalleaves.universe.engine.Kind.MOON }),
        "Asteroids" to n(w.smallBodies?.cometFrom ?: 0),
        "Comets" to n(w.smallBodies?.let { it.count - it.cometFrom } ?: 0),
        "Nebulae and star clusters" to n(w.dsos.size),
        "Galaxies (catalogues)" to n(w.galaxies.count { it.source == com.emotionalleaves.universe.engine.Source.REAL }),
        "Galaxies (GLADE+ pack)" to n(w.glade?.count ?: 0),
        "Pulsars" to n(w.exotic.pulsars.size),
        "Quasars" to n(w.exotic.quasars.size),
        "Galaxy clusters" to n(w.exotic.clusters.size),
        "Descriptions" to n(com.emotionalleaves.universe.engine.Describe.wikiCount),
    )
    val universe = listOf(
        "Galaxies" to "about 2 trillion",
        "Stars" to "about 200,000,000,000,000,000,000,000 (2 × 10²³)",
        "Planets" to "more than stars: about 10²⁴",
        "Rogue planets" to "maybe as many as stars",
        "Neutron stars" to "about 10²¹",
        "Black holes (star-sized)" to "about 10²⁰",
        "Star clusters and nebulae" to "billions",
        "Asteroids, comets and boulders over 1 m" to "about ${com.emotionalleaves.universe.core.Units.sci(2e23 * com.emotionalleaves.universe.engine.RockField.rocksPerStar())}",
        "Beyond what we can see" to "unknown, perhaps infinite (optional in Settings)",
    )
    val near = listOf(
        "Generated stars" to n(w.generated?.count ?: 0),
        "Black holes, neutron stars, rogue planets, clusters, nebulae" to n(w.remnants?.bodies?.size ?: 0),
        "Asteroids in this star system's belts" to n(w.activeSystem?.let { s -> com.emotionalleaves.universe.engine.Belts.forSystem(s).sumOf { it.count } } ?: 0),
        "Galaxies beyond the observable universe" to n(w.outerWeb?.count ?: 0),
        "Generated galaxies" to n(w.field?.count ?: 0),
        "Rocks and boulders around you" to n(w.rocks?.bodies?.size ?: 0),
        "Distant galaxies (cosmic web)" to n(w.cosmicWeb?.count ?: 0),
    )
    // Everything the generators can produce, counted the way they make it (from the setting, not
    // from what has finished loading, so the number is right from the first second).
    val beyondOn = engine.settings.showBeyond && engine.settings.showGenerated
    val observable = com.emotionalleaves.universe.engine.Census.observable()
    val total = com.emotionalleaves.universe.engine.Census.total(beyondOn)
    Panel("What's in the universe", { Nav.screen = Screen.NONE }) {
        Column(Modifier.fillMaxWidth().glass(18.dp, raised = true).padding(16.dp)) {
            Overline("Things you can visit in this app", color = Accent)
            Text(com.emotionalleaves.universe.core.Units.sci(total), style = Type.Title.copy(fontSize = 34.sp, color = Accent))
            Text(
                (if (beyondOn) "Including the speculative universe beyond the edge (9,000 times further out). " else "In the observable universe. Turn on \"Beyond the observable universe\" in Settings for about ${com.emotionalleaves.universe.core.Units.sci(com.emotionalleaves.universe.engine.Census.withBeyond())}. ") +
                    "Counted: every star (2 × 10²³), its planets and moons, black holes, neutron stars, rogue planets, and every asteroid, comet and boulder down to 1 m in its belts and comet cloud (about ${com.emotionalleaves.universe.core.Units.sci(com.emotionalleaves.universe.engine.RockField.rocksPerStar())} per star). " +
                    "Each one is built the moment you get close, and is the same every time.",
                style = Type.Caption, modifier = Modifier.padding(top = 6.dp),
            )
        }
        Text(
            "No phone can store the whole universe, so the app keeps every real catalogue it can fit and builds " +
                "everything else the moment you get close. Generated objects follow real statistics and come out the same every time, " +
                "so every star, planet and galaxy below exists and can be visited.",
            style = Type.BodyDim,
        )
        CensusTable("Real objects in the app", real, RealGreen)
        CensusTable("In the whole observable universe", universe, Accent)
        CensusTable("Generated around you right now", near, GenPurple)
    }
}

@Composable
private fun CensusTable(title: String, rows: List<Pair<String, String>>, color: androidx.compose.ui.graphics.Color) {
    Overline(title, Modifier.padding(top = 20.dp, bottom = 6.dp), color = color)
    Column(Modifier.fillMaxWidth().glass(16.dp, raised = true).padding(horizontal = 14.dp, vertical = 4.dp)) {
        for ((i, kv) in rows.withIndex()) {
            if (i > 0) Hairline()
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                Text(kv.first, style = Type.Caption, modifier = Modifier.weight(0.5f))
                Text(kv.second, style = Type.Caption.copy(color = TextMain, fontFeatureSettings = "tnum"), modifier = Modifier.weight(0.5f))
            }
        }
    }
}
