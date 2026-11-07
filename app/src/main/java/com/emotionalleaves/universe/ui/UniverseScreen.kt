package com.emotionalleaves.universe.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.emotionalleaves.universe.engine.Engine

@Composable
fun UniverseScreen(engine: Engine, version: String, viewFactory: (Context) -> UniverseView) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = viewFactory, modifier = Modifier.fillMaxSize())

        Column(
            Modifier
                .safeDrawingPadding()
                .padding(10.dp)
                .align(Alignment.TopStart)
                .background(Color(0x66000000), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                "${Hud.fps} fps · ${"%.1f".format(Hud.frameMs)} ms",
                color = Color(0xFFB8C0D8), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )
            Text(
                "Distance: ${Hud.distanceText}",
                color = Color(0xFFB8C0D8), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )
        }

        TextButton(
            onClick = { throw RuntimeException("Test crash from the debug button") },
            modifier = Modifier.safeDrawingPadding().align(Alignment.BottomEnd).padding(4.dp),
        ) {
            Text("Test crash · v$version", color = Color(0x665A5F73), fontSize = 10.sp)
        }
    }
}
