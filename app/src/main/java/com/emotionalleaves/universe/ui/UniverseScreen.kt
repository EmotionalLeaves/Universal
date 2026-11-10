package com.emotionalleaves.universe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.emotionalleaves.universe.engine.Engine

@Composable
fun UniverseScreen(engine: Engine, version: String, view: UniverseView) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        engine.packs.onChange = { PacksUi.refresh(engine) }
        SettingsUi.load(engine)
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // The 3D view and the label layer share one Android container, so taps pass through the
        // (non-clickable) labels to the 3D view underneath.
        AndroidView(
            factory = { ctx ->
                android.widget.FrameLayout(ctx).apply {
                    (view.parent as? android.view.ViewGroup)?.removeView(view)
                    addView(view)
                    addView(LabelOverlay(ctx).also { view.renderer.labelOverlay = it })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.align(Alignment.TopStart).fillMaxWidth()) {
                if (!Hud.freeFlight) ZoomSlider(engine, Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp))
                Box(Modifier.fillMaxWidth()) {
                    Column(Modifier.align(Alignment.TopStart).padding(8.dp).fillMaxWidth(0.5f)) {
                        TopStatus(engine)
                        IsolatedChip(engine, Modifier.padding(top = 6.dp))
                        FreeFlightHint(engine, Modifier.padding(top = 6.dp))
                        LoadingChip(Modifier.padding(top = 4.dp))
                        if (SettingsUi.showStats) StatsBadge(Modifier.padding(top = 4.dp))
                    }
                    Column(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalAlignment = Alignment.End) {
                        Row {
                            SearchButton()
                            Spacer(Modifier.width(8.dp))
                            FiltersButton(engine)
                            Spacer(Modifier.width(8.dp))
                            HomeButton(engine)
                            Spacer(Modifier.width(8.dp))
                            MenuButton()
                        }
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            InViewButton()
                            Spacer(Modifier.width(8.dp))
                            FreeFlightButton(engine)
                            Spacer(Modifier.width(8.dp))
                            ExploreButton()
                        }
                    }
                }
            }
            if (Hud.freeFlight) FlightPad(engine, Modifier.align(Alignment.CenterEnd).padding(end = 8.dp))
            Column(
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                InfoCard(engine, Modifier.padding(bottom = 8.dp))
                Box(Modifier.fillMaxWidth()) {
                    Box(Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 4.dp).fillMaxWidth(0.4f)) { ScaleBar() }
                    TimeBar(engine, Modifier.align(Alignment.BottomEnd))
                }
            }
        }
        when (Nav.screen) {
            Screen.EXPLORE -> ExploreScreen(engine)
            Screen.CENSUS -> CensusScreen(engine)
            Screen.MENU -> MenuSheet(engine)
            Screen.DATA -> DataScreen(engine)
            Screen.SEARCH -> SearchScreen(engine)
            Screen.BOOKMARKS -> BookmarksScreen(engine)
            Screen.SETTINGS -> SettingsScreen(engine)
            Screen.FILTERS -> FiltersScreen(engine)
            Screen.STRUCTURES -> StructuresScreen(engine)
            Screen.INVIEW -> InViewScreen(engine)
            Screen.HELP -> HelpScreen()
            Screen.SCALE -> ScaleScreen(engine)
            Screen.CREDITS -> CreditsScreen(version)
            else -> {}
        }
        TipsOverlay(engine)
        // Back closes what is open; with nothing open, press it twice to leave the app.
        androidx.activity.compose.BackHandler(enabled = true) {
            when {
                Nav.screen != Screen.NONE -> Nav.screen = Screen.NONE
                Hud.selectedName.isNotEmpty() -> engine.clearSelection()
                Hud.freeFlight -> { engine.setFreeFlight(false); Hud.freeFlight = false }
                System.currentTimeMillis() - BackUi.last < 2000 -> (view.context as? android.app.Activity)?.finish()
                else -> { BackUi.last = System.currentTimeMillis(); BackUi.hint = true }
            }
        }
        if (BackUi.hint) {
            Text("Press back again to leave", style = Type.Caption.copy(color = TextMain),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp).glass(12.dp).padding(horizontal = 14.dp, vertical = 8.dp))
            androidx.compose.runtime.LaunchedEffect(BackUi.last) { kotlinx.coroutines.delay(2000); BackUi.hint = false }
        }
    }
}

/** Double-back-to-exit state. */
object BackUi {
    var last = 0L
    var hint by androidx.compose.runtime.mutableStateOf(false)
}
