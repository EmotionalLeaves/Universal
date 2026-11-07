package com.emotionalleaves.universe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        AndroidView(
            factory = { ctx -> LabelOverlay(ctx).also { view.renderer.labelOverlay = it } },
            modifier = Modifier.fillMaxSize(),
        )

        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopStatus(Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
            StatsBadge(Modifier.align(Alignment.TopStart).padding(8.dp))
            ZoomSlider(
                engine,
                Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp)) {
                ScaleBar()
            }
            HomeButton(engine, Modifier.align(Alignment.TopEnd).padding(8.dp))
            InfoCard(engine, Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 40.dp))
        }
    }
}
