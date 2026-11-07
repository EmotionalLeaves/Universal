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
            Column(Modifier.align(Alignment.TopStart).padding(8.dp).fillMaxWidth(0.58f)) {
                TopStatus()
                LoadingChip(Modifier.padding(top = 4.dp))
                if (SettingsUi.showStats) StatsBadge(Modifier.padding(top = 4.dp))
            }
            ZoomSlider(
                engine,
                Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
            )
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                SearchButton()
                Spacer(Modifier.width(6.dp))
                HomeButton(engine)
                Spacer(Modifier.width(6.dp))
                MenuButton()
            }
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
            Screen.MENU -> MenuSheet(engine)
            Screen.DATA -> DataScreen(engine)
            Screen.SEARCH -> SearchScreen(engine)
            Screen.BOOKMARKS -> BookmarksScreen(engine)
            Screen.SETTINGS -> SettingsScreen(engine)
            Screen.HELP -> HelpScreen()
            Screen.CREDITS -> CreditsScreen(version)
            else -> {}
        }
        TipsOverlay(engine)
        androidx.activity.compose.BackHandler(enabled = Nav.screen != Screen.NONE || Hud.selectedName.isNotEmpty()) {
            if (Nav.screen != Screen.NONE) Nav.screen = Screen.NONE else engine.clearSelection()
        }
    }
}
