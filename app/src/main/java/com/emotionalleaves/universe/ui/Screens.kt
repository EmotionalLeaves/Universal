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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emotionalleaves.universe.engine.Engine
import com.emotionalleaves.universe.engine.PackState
import java.util.Locale

enum class Screen { NONE, EXPLORE, CENSUS, MENU, DATA, SETTINGS, SEARCH, BOOKMARKS, HELP, CREDITS, SCALE }

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
    IconButton(G.MENU, modifier, active = Nav.screen == Screen.MENU) { Nav.screen = if (Nav.screen == Screen.MENU) Screen.NONE else Screen.MENU }
}

/** Full-screen panel with a title bar and a close button. */
@Composable
fun Panel(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF2080B14))
            .safeDrawingPadding(),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(G.BACK, size = 40.dp) { onClose() }
                Spacer(Modifier.width(12.dp))
                Text(title, style = Type.Title, modifier = Modifier.weight(1f))
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
                .padding(top = 52.dp)
                .widthIn(min = 260.dp, max = 300.dp)
                .glass(18.dp, raised = true)
                .clickable(enabled = false) {}
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Overline("Universe Explorer", Modifier.padding(top = 4.dp, bottom = 2.dp))
            MenuItem(G.COMPASS, "Explore places", kindColor("star")) { Nav.screen = Screen.EXPLORE }
            MenuItem(G.CHART, "What's in the universe", kindColor("galaxy")) { Nav.screen = Screen.CENSUS }
            MenuItem(G.SEARCH, "Search", kindColor("planet")) { Nav.screen = Screen.SEARCH }
            MenuItem(G.RULER, "Sizes compared", kindColor("star")) { ScaleUi.openFor(null); Nav.screen = Screen.SCALE }
            MenuItem(G.BOOKMARK, "Bookmarks", kindColor("nebula")) { Nav.screen = Screen.BOOKMARKS }
            Hairline(Modifier.padding(vertical = 4.dp))
            MenuItem(G.DOWNLOAD, "Data packs", TextDim) { PacksUi.refresh(engine); Nav.screen = Screen.DATA }
            MenuItem(G.SLIDERS, "Settings", TextDim) { Nav.screen = Screen.SETTINGS }
            MenuItem(G.HELP, "How to use", TextDim) { Nav.screen = Screen.HELP }
            MenuItem(G.INFO, "Credits", TextDim) { Nav.screen = Screen.CREDITS }
        }
    }
}

@Composable
private fun MenuItem(g: G, text: String, tint: Color, onClick: () -> Unit) {
    ListRow(text, glyph = g, tint = tint, onClick = onClick)
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
            "The app already includes 109,000 stars, 6,400 exoplanets, 52,000 asteroids, 4,000 comets, every known moon, " +
                "62,000 galaxies, 56,000 quasars and more. Packs add more real data; without them, the gaps are generated.",
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
                    .glass(16.dp, raised = true)
                    .padding(16.dp),
            ) {
                Text(s.info.title, style = Type.Heading)
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
                if (s.error != null) Text(s.error, color = Danger, fontSize = 12.sp)
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
    /** Recently opened results (name, id), newest first; kept between launches. */
    var recent by mutableStateOf<List<Pair<String, String>>>(emptyList())
    private var loaded = false

    fun load(engine: Engine) {
        if (loaded) return
        loaded = true
        val raw = engine.context.getSharedPreferences("search", android.content.Context.MODE_PRIVATE).getString("recent", "") ?: ""
        recent = raw.split("\n").mapNotNull { l -> l.split("\t").takeIf { it.size == 2 }?.let { it[0] to it[1] } }
    }

    fun remember(engine: Engine, name: String, id: String) {
        recent = (listOf(name to id) + recent.filter { it.second != id }).take(8)
        engine.context.getSharedPreferences("search", android.content.Context.MODE_PRIVATE).edit()
            .putString("recent", recent.joinToString("\n") { "${it.first}\t${it.second}" }).apply()
    }

    /** Suggestions shown before you type: record holders and favourites. */
    val popular = listOf(
        "TON 618" to "fam:ton618", "Sagittarius A*" to "fam:sgra", "UY Scuti" to "fam:uyscuti", "Betelgeuse" to "",
        "Andromeda Galaxy" to "", "Pillars of Creation" to "", "Saturn" to "sol:saturn", "Proxima Centauri" to "",
        "M87*" to "fam:m87bh", "Voyager 1" to "fam:voyager1", "Boötes Void" to "fam:bootes", "JADES-GS-z14-0" to "fam:jadesz14",
    )

    var query by mutableStateOf("")
    var results by mutableStateOf<List<com.emotionalleaves.universe.engine.SearchHit>>(emptyList())
    var ready by mutableStateOf(false)
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun SearchScreen(engine: Engine) {
    SearchUi.load(engine)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        engine.prepareSearch {
            SearchUi.ready = true
            engine.searchAsync(SearchUi.query) { SearchUi.results = it }
        }
    }
    val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // Ready to type straight away.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        try { focus.requestFocus(); keyboard?.show() } catch (_: Exception) {}
    }
    Panel("Search", { Nav.screen = Screen.NONE }) {
        androidx.compose.material3.OutlinedTextField(
            value = SearchUi.query,
            onValueChange = {
                SearchUi.query = it
                if (it.isBlank()) SearchUi.results = emptyList() else engine.searchAsync(it) { r -> SearchUi.results = r }
            },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            singleLine = true,
            // The keyboard's search key goes to the best match.
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                SearchUi.results.firstOrNull()?.let { h -> Nav.screen = Screen.NONE; keyboard?.hide(); SearchUi.remember(engine, h.name, h.id); engine.goTo(h.id) }
            }),
            shape = RoundedCornerShape(14.dp),
            leadingIcon = { Glyph(G.SEARCH, size = 18.dp, color = TextDim) },
            placeholder = { Text("TON 618, Mars, Betelgeuse, M31, Kepler-22 b…", color = TextDim, fontSize = 13.sp) },
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextMain, unfocusedTextColor = TextMain, cursorColor = Accent,
                focusedBorderColor = Accent, unfocusedBorderColor = PanelBorder,
            ),
        )
        Spacer(Modifier.height(8.dp))
        fun open(name: String, id: String) {
            Nav.screen = Screen.NONE
            keyboard?.hide()
            SearchUi.remember(engine, name, id)
            if (id.isNotEmpty()) engine.goTo(id) else engine.goToName(name)
        }
        if (!SearchUi.ready && SearchUi.results.isEmpty() && SearchUi.query.isNotBlank()) Text("Building the index…", color = TextDim, fontSize = 13.sp)
        if (SearchUi.query.isBlank()) {
            if (SearchUi.recent.isNotEmpty()) {
                Overline("Recent", modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                for ((name, id) in SearchUi.recent) ListRow(name, "", G.CLOCK, TextDim) { open(name, id) }
            }
            Overline("Popular", modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                for ((name, id) in SearchUi.popular) {
                    Text(
                        name, color = TextMain, fontSize = 13.sp,
                        modifier = Modifier.background(Color(0x14FFFFFF), RoundedCornerShape(16.dp))
                            .clickable { open(name, id) }.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
            Text("Search any planet, moon, star, exoplanet, nebula, galaxy, black hole, quasar or catalogue number (M31, NGC 224, HIP 32349). Spelling does not need to be exact.",
                style = Type.BodyDim, modifier = Modifier.padding(top = 16.dp))
            Button("Browse famous places", Modifier.padding(top = 12.dp), glyph = G.COMPASS) { Nav.screen = Screen.EXPLORE }
        }
        if (SearchUi.ready && SearchUi.query.isNotBlank() && SearchUi.results.isEmpty()) {
            Text("Nothing found for \u201c${SearchUi.query.trim()}\u201d. Try another spelling, or a catalogue number like M31 or HIP 32349.", color = TextDim, fontSize = 13.sp)
        }
        for (h in SearchUi.results) {
            val (g, tint) = glyphForDetail(h.detail)
            ListRow(h.name, h.detail, g, tint) { open(h.name, h.id) }
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
        Button("Bookmark this view", glyph = G.BOOKMARK, primary = true) {
            engine.addBookmark()
            BookmarksUi.list = engine.bookmarks.list()
        }
        Spacer(Modifier.height(12.dp))
        if (BookmarksUi.list.isEmpty()) {
            Text("No bookmarks yet. Tap the bookmark button on any info card, or the button above.", style = Type.BodyDim)
        }
        for (b in BookmarksUi.list) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(1f).clickable {
                        Nav.screen = Screen.NONE
                        engine.openBookmark(b)
                    }.padding(vertical = 6.dp),
                ) {
                    Text(b.name, style = Type.Body.copy(fontWeight = FontWeight.Medium))
                    Text(
                        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(java.util.Date(b.created)),
                        color = TextDim, fontSize = 12.sp,
                    )
                }
                Text("Delete", color = Danger, fontSize = 13.sp, modifier = Modifier.clickable {
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
            Text(title, style = Type.Body)
            if (detail.isNotEmpty()) Text(detail, style = Type.Caption)
        }
        Switch(checked = on, onCheckedChange = { on = it; onChange(it) })
    }
}

@Composable
fun SettingsScreen(engine: Engine) {
    val s = engine.settings
    Panel("Settings", { Nav.screen = Screen.NONE }) {
        Overline("Performance", Modifier.padding(bottom = 6.dp))
        Text("Quality", style = Type.Body)
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
        Overline("Display", Modifier.padding(top = 16.dp, bottom = 2.dp))
        ToggleRow("Labels", "Names next to planets, stars and galaxies", s.showLabels) { s.showLabels = it }
        ToggleRow("Orbits", "Orbit lines of planets and moons", s.showOrbits) { s.showOrbits = it }
        ToggleRow("Constellation lines", "Stick figures between real stars", s.showConstellations) { s.showConstellations = it }
        Overline("Content", Modifier.padding(top = 16.dp, bottom = 2.dp))
        ToggleRow("Generated objects", "Fill gaps in real data with generated stars, planets and galaxies", s.showGenerated) {
            s.showGenerated = it
            engine.world.clearSystems()
            engine.world.refreshBeyond()
        }
        ToggleRow("Beyond the observable universe", "Speculative: the universe continues past the edge we can see, out to 9,000 times further", s.showBeyond) {
            s.showBeyond = it
            engine.world.refreshBeyond()
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
            "Zoom" to "Pinch, or drag the slider on the right. Bottom of the slider = a low orbit over whatever you picked; top = the whole universe.",
            "Pick something" to "Tap a planet, star, nebula or galaxy to see its card; tap it again (or Fly to) to travel there. In crowded spots the card lists what else was under your finger under Also here.",
            "Know more" to "Every card describes the object; tap Read more for the full story, its Wikipedia summary and figures: gravity, your weight there, star type, lifetime, habitable zone and more.",
            "How much is here?" to "Menu → What's in the universe counts the real objects in the app and what is generated as you travel.",
            "Everything is there" to "Tap anywhere on the glow of a galaxy (even the Milky Way band) to pick an actual star at that spot. Fly to it: it has planets. Between galaxies, millions of dwarf and distant galaxies appear around you as you travel.",
            "Move sideways" to "Slide two fingers together. Twist two fingers to roll.",
            "Free flight" to "Tap the paper-plane button (next to Explore) to roam anywhere: drag to look around, hold Forward or Back to fly, pinch for a quick hop. Speed adjusts to what is nearby and grows the longer you hold; the ×1 button goes up to a thousand times faster. In free flight every trip stays free: tap anything twice (or Fly to, Explore, Search) and the autopilot glides you there; Go to nearest takes you to the closest thing. Tap the paper-plane button again to go back to orbiting.",
            "Time" to "Use the rewind, pause and fast-forward buttons at the bottom to change how fast time runs. Now goes back to the present.",
            "Real or generated" to "Every card shows a green Real badge (from scientific catalogues) or a purple Generated badge (made up by rules, the same every time).",
            "Explore" to "Tap Explore (top right) for famous places sorted by type. One tap flies you there. The Surprise me buttons pick a random alien world, galaxy, black hole and more.",
            "Jump between scales" to "The labels beside the zoom slider (Planet, Solar system, Nearby stars, Milky Way, Galaxy groups, Universe, Beyond) jump straight to that size.",
            "Lost in space?" to "If you drift into empty space, tap Back to … under the name at the top left, or the home button.",
            "Find things" to "Search (magnifier, top right) finds planets, stars, exoplanets, asteroids, nebulae and galaxies by name or catalogue number (HIP, HD, NGC, M, PGC).",
            "Save places" to "The bookmark button on any card. Generated places reopen exactly the same.",
            "Home" to "Flies back to Earth.",
        )
        for ((t, d) in items) {
            Text(t, style = Type.Heading.copy(fontSize = 15.sp), modifier = Modifier.padding(top = 14.dp))
            Text(d, style = Type.BodyDim, modifier = Modifier.padding(top = 2.dp))
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
            "JPL Small-Body Database and satellite elements" to "NASA/JPL-Caltech Solar System Dynamics: asteroid and comet orbits, orbits of every known moon.",
            "ATNF Pulsar Catalogue" to "Manchester et al. 2005, AJ 129, 1993 (via VizieR).",
            "Million Quasars (Milliquas)" to "Flesch 2023 (via VizieR VII/294).",
            "Abell catalogue of rich clusters" to "Abell, Corwin & Olowin 1989 (via VizieR VII/110A).",
            "Wikipedia" to "\"About\" texts are summaries of Wikipedia articles, CC BY-SA 4.0; each card names its article.",
            "Cosmicflows-4" to "Tully et al. 2023, ApJ 944, 94 (galaxy distances), via VizieR (CDS).",
            "GLADE+" to "Dálya et al. 2022, MNRAS 514, 1403, via VizieR (CDS).",
            "Planck" to "Based on observations obtained with Planck (ESA), SMICA CMB map, via IRSA.",
            "Planet orbits" to "E. M. Standish and J. G. Williams, JPL Approximate Positions of the Planets.",
            "Planet maps" to "three.js example textures (NASA-based), Solar System Scope (CC BY 4.0), Planet Pixel Emporium via threex.planets.",
            "Constellations" to "Stellarium modern sky culture.",
            "Generated content" to "Stars, planets, galaxy interiors and the distant cosmic web are generated from seeded rules; they are labelled Generated everywhere.",
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
        "A universe of ${com.emotionalleaves.universe.core.Units.sci(com.emotionalleaves.universe.engine.Census.withBeyond())} things" to "Every star in every galaxy, with its planets, moons and every rock down to 1 metre, is built the moment you get close — and is the same every time. Explore → the count shows how it adds up.",
        "Zoom anywhere" to "Pinch, or drag the slider on the right. Tap a label beside it (Planet, Solar system, Milky Way…) to jump to that scale.",
        "Not sure where to go?" to "Tap Explore (top right) for famous planets, stars, nebulae and galaxies, or Surprise me for a random alien world.",
        "Tap to explore" to "Tap anything for its card. Tap it again, or Fly to, to travel there.",
        "Fly freely" to "The paper-plane button next to Explore switches to free flight: look around and fly anywhere, at a speed that adapts to what is near you.",
        "Every star is real to visit" to "Tap anywhere on the glow of a galaxy, even the Milky Way band, to pick a star at that spot. Each one has planets to fly to.",
        "Real or generated" to "Green Real = from scientific catalogues. Purple Generated = filled in by rules where nobody has measured yet.",
        "Find and save" to "The magnifier searches by name, the house flies home, and the menu has bookmarks, data packs and settings.",
    )
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable { }) {
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(24.dp)
                .widthIn(max = 420.dp)
                .glass(22.dp, raised = true)
                .padding(22.dp),
        ) {
            val (t, d) = tips[page]
            Overline("Welcome tour", color = Accent)
            Text(t, style = Type.Title, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            Text(d, style = Type.Body.copy(color = TextDim, fontSize = 15.sp, lineHeight = 22.sp))
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (k in tips.indices) Box(Modifier.size(if (k == page) 18.dp else 6.dp, 6.dp).background(if (k == page) Accent else TextFaint, RoundedCornerShape(3.dp)))
                }
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

/** Icon and colour for a search result, from its detail line. */
private fun glyphForDetail(d: String): Pair<G, Color> {
    val l = d.lowercase(Locale.US)
    return when {
        "exoplanet" in l || "planet" in l -> G.PLANET to kindColor("planet")
        "moon" in l -> G.MOON to kindColor("moon")
        "asteroid" in l || "comet" in l || "trojan" in l || "neptunian" in l || "centaur" in l -> G.ASTEROID to kindColor("asteroid")
        "galaxy cluster" in l -> G.GALAXY to kindColor("galaxy cluster")
        "galaxy" in l || "spiral" in l || "elliptical" in l || "lenticular" in l || "irregular" in l -> G.GALAXY to kindColor("galaxy")
        "black hole" in l && "quasar" !in l -> G.BLACKHOLE to kindColor("black hole")
        "pulsar" in l || "quasar" in l -> G.SPARKLE to kindColor("pulsar")
        "void" in l || "supercluster" in l || "wall" in l -> G.UNIVERSE to kindColor("galaxy")
        "spacecraft" in l || "probe" in l -> G.TARGET to TextMain
        "nebula" in l || "cluster" in l || "remnant" in l -> G.NEBULA to kindColor("nebula")
        "universe" in l -> G.UNIVERSE to TextDim
        "star" in l || "sun" in l -> G.STAR to kindColor("star")
        else -> G.TARGET to TextDim
    }
}
