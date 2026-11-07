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

enum class Screen { NONE, MENU, DATA, SETTINGS, SEARCH, BOOKMARKS, HELP, CREDITS }

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
