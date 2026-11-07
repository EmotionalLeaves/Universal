package com.emotionalleaves.universe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emotionalleaves.universe.engine.Engine
import com.emotionalleaves.universe.engine.PackState
import java.util.Locale

enum class Screen { NONE, EXPLORE, MENU, DATA, SETTINGS, SEARCH, BOOKMARKS, HELP, CREDITS }

/** Which overlay screen is open. */
object Nav {
    var screen by mutableStateOf(Screen.NONE)
}

/** Pack states for Compose, refreshed by the PackManager. */
object PacksUi {
    var states by mutableStateOf<List<PackState>>(emptyList())
    var totalBytes by mutableStateOf(0L)
    var wifiOnly by mutableStateOf(true)

    fun refresh(engine: Engine) {
        states = engine.packs.states()
        totalBytes = engine.packs.totalBytes()
        wifiOnly = engine.settings.wifiOnly
    }
}

@Composable
fun MenuButton(modifier: Modifier = Modifier) {
    PillButton("☰", modifier) { Nav.screen = if (Nav.screen == Screen.NONE) Screen.MENU else Screen.NONE }
}

/** Full-screen panel with a title bar and a close button. */
@Composable
fun Panel(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE6070912))
            .safeDrawingPadding(),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("✕", color = TextDim, fontSize = 22.sp, modifier = Modifier.clickable { onClose() }.padding(8.dp))
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                content()
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
fun MenuSheet(engine: Engine) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { Nav.screen = Screen.NONE }) {
        Column(
            Modifier
                .safeDrawingPadding()
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .widthIn(min = 220.dp)
                .background(PanelBg, RoundedCornerShape(16.dp))
                .border(1.dp, PanelBorder, RoundedCornerShape(16.dp))
                .padding(vertical = 6.dp),
        ) {
            MenuItem("Explore places") { Nav.screen = Screen.EXPLORE }
            MenuItem("Search") { Nav.screen = Screen.SEARCH }
            MenuItem("Bookmarks") { Nav.screen = Screen.BOOKMARKS }
            MenuItem("Data packs") { PacksUi.refresh(engine); Nav.screen = Screen.DATA }
            MenuItem("Settings") { Nav.screen = Screen.SETTINGS }
            MenuItem("How to use") { Nav.screen = Screen.HELP }
            MenuItem("Credits") { Nav.screen = Screen.CREDITS }
        }
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 18.dp, vertical = 12.dp),
        color = TextMain, fontSize = 16.sp,
    )
}

fun formatBytes(b: Long): String = when {
    b >= 1_000_000_000 -> String.format(Locale.US, "%.2f GB", b / 1e9)
    b >= 1_000_000 -> String.format(Locale.US, "%.1f MB", b / 1e6)
    b >= 1_000 -> String.format(Locale.US, "%.0f KB", b / 1e3)
    else -> "$b B"
}

@Composable
fun DataScreen(engine: Engine) {
    Panel("Data packs", { Nav.screen = Screen.NONE }) {
        Text(
            "The app already includes the Sun's neighbourhood, 109,000 bright stars, 6,000+ exoplanets and " +
                "62,000 galaxies. Packs add more real data; without them, the gaps are generated.",
            color = TextDim, fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Wi-Fi only", color = TextMain, fontSize = 15.sp)
                Text("Don't download on mobile data", color = TextDim, fontSize = 12.sp)
            }
            Switch(checked = PacksUi.wifiOnly, onCheckedChange = {
                engine.settings.wifiOnly = it
                PacksUi.refresh(engine)
            })
        }
        Spacer(Modifier.height(8.dp))
        Text("Storage used by packs: ${formatBytes(PacksUi.totalBytes)}", color = TextDim, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        for (s in PacksUi.states) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .background(PanelBg, RoundedCornerShape(14.dp))
                    .border(1.dp, PanelBorder, RoundedCornerShape(14.dp))
                    .padding(14.dp),
            ) {
                Text(s.info.title, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(s.info.description, color = TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        s.downloading -> "Downloading… ${(s.progress * 100).toInt()}%"
                        s.installed -> "Installed · ${formatBytes(s.sizeBytes)}"
                        else -> "Not installed · about ${s.info.approxMb} MB download"
                    },
                    color = if (s.installed) RealGreen else TextDim, fontSize = 12.sp,
                )
                if (s.error != null) Text(s.error, color = Color(0xFFFF8A8A), fontSize = 12.sp)
                if (s.downloading) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        s.downloading -> PillButton("Cancel") { engine.packs.cancel(s.info.id) }
                        s.installed -> PillButton("Delete") { engine.packs.delete(s.info.id) }
                        else -> PillButton("Download", primary = true) { engine.packs.download(s.info.id) }
                    }
                }
            }
        }
    }
}

@Composable
fun Spacer8() = Spacer(Modifier.width(8.dp))

object SearchUi {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<com.emotionalleaves.universe.engine.SearchHit>>(emptyList())
    var ready by mutableStateOf(false)
}

@Composable
fun SearchScreen(engine: Engine) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        engine.prepareSearch {
            SearchUi.ready = true
            SearchUi.results = engine.search.search(SearchUi.query)
        }
    }
    Panel("Search", { Nav.screen = Screen.NONE }) {
        androidx.compose.material3.OutlinedTextField(
            value = SearchUi.query,
            onValueChange = {
                SearchUi.query = it
                SearchUi.results = engine.search.search(it)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Mars, Sirius, Kepler-22 b, Orion Nebula, M31, HIP 32349…", color = TextDim, fontSize = 13.sp) },
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextMain, unfocusedTextColor = TextMain, cursorColor = Accent,
                focusedBorderColor = Accent, unfocusedBorderColor = PanelBorder,
            ),
        )
        Spacer(Modifier.height(8.dp))
        if (!SearchUi.ready) Text("Building the index…", color = TextDim, fontSize = 13.sp)
        if (SearchUi.query.isBlank()) {
            Text("Don't know what to look for?", color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            PillButton("✦ Browse famous places", Modifier.padding(top = 6.dp), primary = true) { Nav.screen = Screen.EXPLORE }
        }
        if (SearchUi.ready && SearchUi.query.isNotBlank() && SearchUi.results.isEmpty()) {
            Text("Nothing found. Try a planet, star, nebula, galaxy or catalogue number.", color = TextDim, fontSize = 13.sp)
        }
        for (h in SearchUi.results) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        Nav.screen = Screen.NONE
                        engine.goTo(h.id)
                    }
                    .padding(vertical = 10.dp),
            ) {
                Text(h.name, color = TextMain, fontSize = 16.sp)
                Text(h.detail, color = TextDim, fontSize = 12.sp)
            }
        }
    }
}

object BookmarksUi {
    var list by mutableStateOf<List<com.emotionalleaves.universe.engine.Bookmark>>(emptyList())
    var toast by mutableStateOf("")
}

@Composable
fun BookmarksScreen(engine: Engine) {
    androidx.compose.runtime.LaunchedEffect(Unit) { BookmarksUi.list = engine.bookmarks.list() }
    Panel("Bookmarks", { Nav.screen = Screen.NONE }) {
        PillButton("★ Bookmark this view", primary = true) {
            engine.addBookmark()
            BookmarksUi.list = engine.bookmarks.list()
        }
        Spacer(Modifier.height(12.dp))
        if (BookmarksUi.list.isEmpty()) {
            Text("No bookmarks yet. Tap ★ on any info card, or the button above.", color = TextDim, fontSize = 13.sp)
        }
        for (b in BookmarksUi.list) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(1f).clickable {
                        Nav.screen = Screen.NONE
                        engine.openBookmark(b)
                    }.padding(vertical = 6.dp),
                ) {
                    Text(b.name, color = TextMain, fontSize = 16.sp)
                    Text(
                        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(java.util.Date(b.created)),
                        color = TextDim, fontSize = 12.sp,
                    )
                }
                Text("Delete", color = Color(0xFFFF9A9A), fontSize = 13.sp, modifier = Modifier.clickable {
                    engine.bookmarks.remove(b)
                    BookmarksUi.list = engine.bookmarks.list()
                }.padding(8.dp))
            }
        }
    }
}

/** Settings mirrored into Compose state so toggles redraw immediately. */
object SettingsUi {
    var showStats by mutableStateOf(false)
    var tipsVisible by mutableStateOf(false)

    fun load(engine: Engine) {
        showStats = engine.settings.showStats
        tipsVisible = !engine.settings.seenTips
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var on by androidx.compose.runtime.remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 15.sp)
            if (detail.isNotEmpty()) Text(detail, color = TextDim, fontSize = 12.sp)
        }
        Switch(checked = on, onCheckedChange = { on = it; onChange(it) })
    }
}

@Composable
fun SettingsScreen(engine: Engine) {
    val s = engine.settings
    Panel("Settings", { Nav.screen = Screen.NONE }) {
        Text("Quality", color = TextMain, fontSize = 15.sp)
        Text("Lower uses less battery and keeps older phones smooth.", color = TextDim, fontSize = 12.sp)
        var q by androidx.compose.runtime.remember { mutableStateOf(s.quality) }
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Low", "Medium", "High").forEachIndexed { i, label ->
                PillButton(label, primary = q == i) {
                    q = i
                    s.quality = i
                    engine.world.packsChanged()
                }
            }
        }
        ToggleRow("Battery saver", "Limits to 30 frames per second", s.batterySaver) { s.batterySaver = it }
        ToggleRow("Labels", "Names next to planets, stars and galaxies", s.showLabels) { s.showLabels = it }
        ToggleRow("Orbits", "Orbit lines of planets and moons", s.showOrbits) { s.showOrbits = it }
        ToggleRow("Constellation lines", "Stick figures between real stars", s.showConstellations) { s.showConstellations = it }
        ToggleRow("Generated objects", "Fill gaps in real data with generated stars, planets and galaxies", s.showGenerated) {
            s.showGenerated = it
            engine.world.clearSystems()
        }
        Text("Star brightness", color = TextMain, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
        var b by androidx.compose.runtime.remember { mutableStateOf(s.starBrightness) }
        androidx.compose.material3.Slider(
            value = b, valueRange = -1.5f..3f,
            onValueChange = { b = it; s.starBrightness = it },
        )
        ToggleRow("Frame rate counter", "Shows frames per second in the corner", s.showStats) {
            s.showStats = it
            SettingsUi.showStats = it
        }
        ToggleRow("Scale-test objects", "1 m cube on an Earth-sized sphere, and a marker 10 billion ly away", s.showTestObjects) {
            s.showTestObjects = it
        }
        Spacer(Modifier.height(16.dp))
        val problem = androidx.compose.runtime.remember { com.emotionalleaves.universe.engine.ErrorLog.read() }
        if (problem != null) {
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            Text("Last problem (the app kept running)", color = TextMain, fontSize = 15.sp)
            Text(problem.lines().take(4).joinToString("\n"), color = TextDim, fontSize = 11.sp)
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Copy report") { clipboard.setText(androidx.compose.ui.text.AnnotatedString(problem)) }
                PillButton("Clear") { com.emotionalleaves.universe.engine.ErrorLog.clear() }
            }
        }
        Spacer(Modifier.height(12.dp))
        PillButton("Show the tips again") {
            s.seenTips = false
            SettingsUi.tipsVisible = true
            Nav.screen = Screen.NONE
        }
    }
}

@Composable
fun HelpScreen() {
    Panel("How to use", { Nav.screen = Screen.NONE }) {
        val items = listOf(
            "Look around" to "Drag with one finger to orbit whatever you are looking at.",
            "Zoom" to "Pinch, or drag the slider on the right. Bottom of the slider = standing on the ground; top = the whole observable universe.",
            "Pick something" to "Tap a planet, star, nebula or galaxy to see its card. Double-tap (or Fly to) to travel there.",
            "Everything is there" to "Tap anywhere on the glow of a galaxy (even the Milky Way band) to pick an actual star at that spot. Fly to it: it has planets, and rocky ones can be landed on. Between galaxies, millions of dwarf and distant galaxies appear around you as you travel.",
            "Move sideways" to "Slide two fingers together. Twist two fingers to roll.",
            "On the ground" to "Zoom all the way into a rocky planet or moon. Drag up/down to walk, left/right to turn.",
            "Time" to "Use ⏪ ⏸ ⏩ at the bottom to change how fast time runs. Now goes back to the present.",
            "Real or generated" to "Every card shows a green Real badge (from scientific catalogues) or a purple Generated badge (made up by rules, the same every time).",
            "Explore" to "Tap ✦ Explore (top right) for a list of famous places sorted by type. One tap flies you there. Surprise me picks a random alien world.",
            "Jump between scales" to "The labels beside the zoom slider (Ground, Planet, Solar system, Nearby stars, Milky Way, Galaxy groups, Universe) jump straight to that size.",
            "Lost in space?" to "If you drift into empty space, tap ↩ Back to … under the name at the top left, or Home.",
            "Find things" to "🔍 Search finds planets, stars, exoplanets, nebulae and galaxies by name or catalogue number (HIP, HD, NGC, M, PGC).",
            "Save places" to "★ Bookmark on any card. Generated places reopen exactly the same.",
            "Home" to "Flies back to Earth.",
        )
        for ((t, d) in items) {
            Text(t, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
            Text(d, color = TextDim, fontSize = 13.sp)
        }
    }
}

@Composable
fun CreditsScreen(version: String) {
    Panel("Credits", { Nav.screen = Screen.NONE }) {
        Text("Universe Explorer $version", color = TextMain, fontSize = 15.sp)
        Text("App code: MIT licence.", color = TextDim, fontSize = 13.sp)
        val items = listOf(
            "HYG v4.4 and AT-HYG v4.0 star catalogues" to "© Astronexus (David Nash), CC BY-SA 4.0. Built on Hipparcos, Yale Bright Star, Gliese, Tycho-2 and Gaia DR3. Star tiles derived from them are also CC BY-SA 4.0.",
            "Gaia" to "This app uses data from the ESA mission Gaia, processed by the Gaia DPAC (via AT-HYG).",
            "NASA Exoplanet Archive" to "This research has made use of the NASA Exoplanet Archive, operated by Caltech under contract with NASA.",
            "OpenNGC" to "Mattia Verga, CC BY-SA 4.0 (nebulae, clusters, NGC/IC galaxies).",
            "Cosmicflows-4" to "Tully et al. 2023, ApJ 944, 94 (galaxy distances), via VizieR (CDS).",
            "GLADE+" to "Dálya et al. 2022, MNRAS 514, 1403, via VizieR (CDS).",
            "Planck" to "Based on observations obtained with Planck (ESA), SMICA CMB map, via IRSA.",
            "Planet orbits" to "E. M. Standish and J. G. Williams, JPL Approximate Positions of the Planets.",
            "Elevation" to "Earth: NOAA ETOPO1. Mars: MGS MOLA (NASA/USGS). Moon: LRO LOLA (NASA SVS CGI Moon Kit).",
            "Planet maps" to "three.js example textures (NASA-based), Solar System Scope (CC BY 4.0), Planet Pixel Emporium via threex.planets.",
            "Constellations" to "Stellarium modern sky culture.",
            "Generated content" to "Stars, planets, terrain, galaxy interiors and the distant cosmic web are generated from seeded rules; they are labelled Generated everywhere.",
        )
        for ((t, d) in items) {
            Text(t, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
            Text(d, color = TextDim, fontSize = 13.sp)
        }
    }
}

/** Shown once on first launch. */
@Composable
fun TipsOverlay(engine: Engine) {
    if (!SettingsUi.tipsVisible) return
    var page by androidx.compose.runtime.remember { mutableStateOf(0) }
    val tips = listOf(
        "Welcome!" to "You are looking at Earth, today, from space. Everything you see is in its real place.",
        "Zoom anywhere" to "Pinch, or drag the slider on the right. Tap a label beside it (Planet, Solar system, Milky Way…) to jump to that scale.",
        "Not sure where to go?" to "Tap ✦ Explore (top right) for famous planets, stars, nebulae and galaxies, or Surprise me for a random alien world.",
        "Tap to explore" to "Tap anything for its card. Double-tap or Fly to to travel there.",
        "Real or generated" to "Green Real = from scientific catalogues. Purple Generated = filled in by rules where nobody has measured yet.",
        "Find and save" to "🔍 searches by name. 🏠 flies home. ☰ has bookmarks, data packs and settings.",
    )
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable { }) {
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(24.dp)
                .widthIn(max = 420.dp)
                .background(PanelBg, RoundedCornerShape(18.dp))
                .border(1.dp, PanelBorder, RoundedCornerShape(18.dp))
                .padding(20.dp),
        ) {
            val (t, d) = tips[page]
            Text(t, color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(d, color = TextDim, fontSize = 15.sp)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${page + 1} / ${tips.size}", color = TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (page < tips.size - 1) {
                    Text("Skip", color = TextDim, fontSize = 14.sp, modifier = Modifier.clickable {
                        engine.settings.seenTips = true
                        SettingsUi.tipsVisible = false
                    }.padding(10.dp))
                    PillButton("Next", primary = true) { page++ }
                } else {
                    PillButton("Start exploring", primary = true) {
                        engine.settings.seenTips = true
                        SettingsUi.tipsVisible = false
                    }
                }
            }
        }
    }
}
