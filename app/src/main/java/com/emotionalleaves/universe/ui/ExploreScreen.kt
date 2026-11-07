package com.emotionalleaves.universe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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

private class Category(val icon: String, val title: String, val blurb: String, val places: List<Place>)

private fun p(label: String, hint: String, query: String = label) = Place(label, query, hint)

private val CATEGORIES = listOf(
    Category(
        "☀", "Solar system", "Our Sun, its planets and their moons. Rocky ones can be landed on.",
        listOf(
            p("Sun", "Our star"), p("Mercury", "Smallest planet"), p("Venus", "Hottest planet"),
            p("Earth", "Home"), p("Moon", "Walk on real lunar terrain"), p("Mars", "Real terrain from MOLA"),
            p("Jupiter", "Largest planet"), p("Io", "Volcanic moon of Jupiter"), p("Europa", "Icy moon of Jupiter"),
            p("Saturn", "Rings"), p("Titan", "Hazy moon of Saturn"), p("Uranus", "Tilted on its side"),
            p("Neptune", "Farthest planet"), p("Pluto", "Dwarf planet"),
        ),
    ),
    Category(
        "☄", "Asteroids and comets", "Real orbits from NASA JPL for tens of thousands of them.",
        listOf(
            p("Ceres", "Largest asteroid, a dwarf planet", "1 Ceres"), p("Vesta", "Giant asteroid with a huge crater", "4 Vesta"),
            p("Pallas", "Third-largest asteroid", "2 Pallas"), p("Eros", "First asteroid a spacecraft landed on", "433 Eros"),
            p("Bennu", "Sampled by OSIRIS-REx", "101955 Bennu"), p("Ryugu", "Sampled by Hayabusa2", "162173 Ryugu"),
            p("Halley's Comet", "Returns every 76 years", "1P/Halley"), p("Comet 67P", "Visited by Rosetta", "67P/Churyumov-Gerasimenko"),
            p("Encke's Comet", "Shortest-period big comet", "2P/Encke"),
        ),
    ),
    Category(
        "◐", "More moons", "Every known moon of the planets, with real orbits.",
        listOf(
            p("Triton", "Neptune's moon, orbits backwards"), p("Phoebe", "Saturn's captured outer moon"),
            p("Enceladus", "Ice geysers over a hidden ocean"), p("Miranda", "Patchwork moon of Uranus"),
            p("Charon", "Pluto's giant moon"), p("Himalia", "Largest irregular moon of Jupiter"),
        ),
    ),
    Category(
        "✶", "Famous stars", "Bright and well-known stars, all at their real positions.",
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
        "◉", "Exoplanets", "Real planets around other stars (NASA Exoplanet Archive).",
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
        "☁", "Nebulae and clusters", "Gas clouds and star clusters in the Milky Way (OpenNGC).",
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
        "🌀", "Galaxies", "Our galaxy and its neighbours. Zoom out further for millions more.",
        listOf(
            p("Milky Way", "Our galaxy, seen from outside"),
            p("Large Magellanic Cloud", "Satellite galaxy, 160,000 ly"),
            p("Small Magellanic Cloud", "Satellite galaxy"),
            p("Andromeda Galaxy", "Nearest big galaxy, 2.5 Mly"), p("Triangulum Galaxy", "Third-largest in our group"),
            p("Whirlpool Galaxy", "Classic spiral with a companion"), p("Pinwheel Galaxy", "Face-on spiral"),
            p("Sombrero Galaxy", "Edge-on with a dust lane"), p("Cigar Galaxy", "Starburst galaxy"),
            p("Centaurus A", "Giant galaxy with a black-hole jet"),
        ),
    ),
    Category(
        "⚫", "Strange things", "Dead stars, black holes and the brightest objects in the universe.",
        listOf(
            p("Crab Pulsar", "Spins 30 times a second", "PSR J0534+2200"), p("Vela Pulsar", "Spins 11 times a second", "PSR J0835-4510"),
            p("3C 273", "First quasar ever identified"), p("Coma Cluster", "Over 1,000 galaxies", "Abell 1656"),
            p("Perseus Cluster", "Brightest X-ray cluster", "Abell 426"),
        ),
    ),
    Category(
        "◎", "The biggest scale", "The cosmic web and the edge of what we can see.",
        listOf(p("Observable universe", "Everything, out to the CMB")),
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
        Text(
            "Tap a place to fly there. Tap a heading to open or close it.",
            color = TextDim, fontSize = 13.sp,
        )
        Spacer(Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth()) {
            PillButton("🎲 Alien world", Modifier.weight(1f), primary = true) {
                engine.surprise(land = true); Nav.screen = Screen.NONE
            }
            Spacer(Modifier.width(8.dp))
            PillButton("🎲 Any planet", Modifier.weight(1f)) {
                engine.surprise(land = false); Nav.screen = Screen.NONE
            }
            Spacer(Modifier.width(8.dp))
            PillButton("🎲 Galaxy", Modifier.weight(1f)) {
                engine.surpriseGalaxy(); Nav.screen = Screen.NONE
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            PillButton("🎲 Black hole", Modifier.weight(1f)) { engine.surpriseRemnant(com.emotionalleaves.universe.engine.Kind.BLACK_HOLE); Nav.screen = Screen.NONE }
            Spacer(Modifier.width(8.dp))
            PillButton("🎲 Neutron star", Modifier.weight(1f)) { engine.surpriseRemnant(com.emotionalleaves.universe.engine.Kind.NEUTRON_STAR); Nav.screen = Screen.NONE }
            Spacer(Modifier.width(8.dp))
            PillButton("🎲 Rogue planet", Modifier.weight(1f)) { engine.surpriseRemnant(com.emotionalleaves.universe.engine.Kind.ROGUE_PLANET); Nav.screen = Screen.NONE }
        }
        Text(
            "Alien world and Any planet pick a real star and visit one of its generated planets. Galaxy picks one of the millions far away.",
            color = TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
        )
        for (c in CATEGORIES) {
            val isOpen = c.title in open
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .clickable { open = if (isOpen) open - c.title else open + c.title }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(c.icon, color = Accent, fontSize = 18.sp, modifier = Modifier.width(30.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.title, color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(c.blurb, color = TextDim, fontSize = 12.sp)
                }
                Text(if (isOpen) "▾" else "▸", color = TextDim, fontSize = 18.sp)
            }
            if (isOpen) {
                for (pl in c.places) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .background(PanelBg, RoundedCornerShape(12.dp))
                            .border(1.dp, PanelBorder, RoundedCornerShape(12.dp))
                            .clickable { engine.goToName(pl.query); Nav.screen = Screen.NONE }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(pl.label, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(pl.hint, color = TextDim, fontSize = 12.sp)
                        }
                        Text("Fly →", color = Accent, fontSize = 13.sp)
                    }
                }
            }
        }
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
    )
    val near = listOf(
        "Generated stars" to n(w.generated?.count ?: 0),
        "Black holes, neutron stars, rogue planets" to n(w.remnants?.bodies?.size ?: 0),
        "Generated galaxies" to n(w.field?.count ?: 0),
        "Distant galaxies (cosmic web)" to n(w.cosmicWeb?.count ?: 0),
    )
    Panel("What's in the universe", { Nav.screen = Screen.NONE }) {
        Text(
            "No phone can store the whole universe, so the app keeps every real catalogue it can fit and builds " +
                "everything else the moment you get close. Generated objects follow real statistics and come out the same every time, " +
                "so every star, planet and galaxy below exists and can be visited.",
            color = TextDim, fontSize = 13.sp,
        )
        CensusTable("Real objects in the app (green Real badge)", real, RealGreen)
        CensusTable("In the whole observable universe (all reachable here)", universe, Accent)
        CensusTable("Generated around you right now (purple badge)", near, GenPurple)
    }
}

@Composable
private fun CensusTable(title: String, rows: List<Pair<String, String>>, color: androidx.compose.ui.graphics.Color) {
    Text(title, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
    for ((k, v) in rows) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(k, color = TextDim, fontSize = 13.sp, modifier = Modifier.weight(0.5f))
            Text(v, color = TextMain, fontSize = 13.sp, modifier = Modifier.weight(0.5f))
        }
    }
}
