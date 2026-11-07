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
import androidx.compose.runtime.remember
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
        "◎", "The biggest scale", "The cosmic web and the edge of what we can see.",
        listOf(p("Observable universe", "Everything, out to the CMB")),
    ),
)

/** Browsable list of famous places, so you don't need to know names to search for. */
@Composable
fun ExploreScreen(engine: Engine) {
    var open by remember { mutableStateOf(setOf("Solar system")) }
    Panel("Explore", { Nav.screen = Screen.NONE }) {
        Text(
            "Tap a place to fly there. Tap a heading to open or close it.",
            color = TextDim, fontSize = 13.sp,
        )
        Spacer(Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth()) {
            PillButton("🎲 Surprise me: alien world", Modifier.weight(1f), primary = true) {
                engine.surprise(land = true); Nav.screen = Screen.NONE
            }
            Spacer(Modifier.width(8.dp))
            PillButton("🎲 Any planet", Modifier) {
                engine.surprise(land = false); Nav.screen = Screen.NONE
            }
        }
        Text(
            "Surprise me picks a real star and visits one of its generated planets.",
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
