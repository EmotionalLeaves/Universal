package com.emotionalleaves.universe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emotionalleaves.universe.engine.Engine

val PanelBg = Color(0xCC0B0E18)
val PanelBorder = Color(0x33FFFFFF)
val TextMain = Color(0xFFE8ECF8)
val TextDim = Color(0xFF9AA3BC)
val Accent = Color(0xFFFFD27A)
val RealGreen = Color(0xFF7BE0A0)
val GenPurple = Color(0xFFC3A6FF)

@Composable
fun TopStatus(modifier: Modifier = Modifier) {
    if (Hud.focusName.isEmpty()) return
    Column(
        modifier
            .background(PanelBg, RoundedCornerShape(14.dp))
            .border(1.dp, PanelBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(Hud.focusName, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(
            "Distance ${Hud.distanceText}" + if (Hud.altitudeText.isNotEmpty()) "  ·  ${Hud.altitudeText}" else "",
            color = TextDim, fontSize = 12.sp, maxLines = 1,
        )
    }
}

@Composable
fun StatsBadge(modifier: Modifier = Modifier) {
    Text(
        "${Hud.fps} fps",
        modifier = modifier
            .background(Color(0x55000000), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = Color(0xFF8A93AC), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
    )
}

@Composable
fun ScaleBar() {
    val screenW = LocalConfiguration.current.screenWidthDp.toFloat()
    if (Hud.scaleBarText.isEmpty()) return
    val barW = (screenW * Hud.scaleBarFraction).coerceIn(20f, screenW * 0.4f)
    Column {
        Text(Hud.scaleBarText, color = TextMain, fontSize = 11.sp)
        Spacer(Modifier.height(2.dp))
        Box(
            Modifier
                .width(barW.dp)
                .height(4.dp)
                .background(Color(0xCCE8ECF8), RoundedCornerShape(2.dp)),
        )
    }
}

/**
 * Vertical zoom slider on a logarithmic scale: bottom = touching the surface, top = the whole
 * observable universe. Drag it to cross the universe in one swipe.
 */
@Composable
fun ZoomSlider(engine: Engine, modifier: Modifier = Modifier) {
    var trackH by remember { mutableStateOf(1) }
    val density = LocalDensity.current
    Box(
        modifier
            .width(44.dp)
            .height(260.dp)
            .onSizeChanged { trackH = it.height }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o ->
                        Hud.sliderDragging = true
                        val f = 1f - (o.y / trackH).coerceIn(0f, 1f)
                        Hud.zoomFraction = f
                        engine.setZoomFraction(f)
                    },
                    onDragEnd = { Hud.sliderDragging = false },
                    onDragCancel = { Hud.sliderDragging = false },
                ) { change, _ ->
                    val f = 1f - (change.position.y / trackH).coerceIn(0f, 1f)
                    Hud.zoomFraction = f
                    engine.setZoomFraction(f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    val f = 1f - (o.y / trackH).coerceIn(0f, 1f)
                    Hud.zoomFraction = f
                    engine.setZoomFraction(f)
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(260.dp)
                .background(Color(0x55FFFFFF), RoundedCornerShape(2.dp)),
        )
        val y = with(density) { ((1f - Hud.zoomFraction) * trackH).toDp() } - 11.dp
        Box(
            Modifier
                .offset(y = y)
                .size(22.dp)
                .background(Accent, CircleShape)
                .border(2.dp, Color(0xAA000000), CircleShape),
        )
        Text("+", color = TextDim, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomCenter).offset(y = 18.dp))
        Text("−", color = TextDim, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopCenter).offset(y = (-18).dp))
    }
}

@Composable
fun HomeButton(engine: Engine, modifier: Modifier = Modifier) {
    PillButton("Home", modifier) { engine.goHome() }
}

@Composable
fun PillButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        modifier = modifier
            .background(if (primary) Accent else PanelBg, RoundedCornerShape(18.dp))
            .border(1.dp, if (primary) Color.Transparent else PanelBorder, RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = if (primary) Color(0xFF1A1405) else TextMain,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun SourceBadge(source: String) {
    if (source.isEmpty()) return
    val c = if (source == "Real") RealGreen else GenPurple
    Text(
        source,
        modifier = Modifier
            .border(1.dp, c, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 1.dp),
        color = c, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun InfoCard(engine: Engine, modifier: Modifier = Modifier) {
    if (Hud.selectedName.isEmpty()) return
    Column(
        modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .background(PanelBg, RoundedCornerShape(18.dp))
            .border(1.dp, PanelBorder, RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    Hud.selectedName, color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(Hud.selectedSubtitle, color = TextDim, fontSize = 13.sp, maxLines = 2)
            }
            SourceBadge(Hud.selectedSource)
            Spacer(Modifier.width(8.dp))
            Text(
                "✕", color = TextDim, fontSize = 18.sp,
                modifier = Modifier.clickable { engine.clearSelection() }.padding(6.dp),
            )
        }
        if (Hud.selectedDistance.isNotEmpty()) {
            Text("${Hud.selectedDistance} from you", color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        val facts = Hud.selectedFacts
        if (facts.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            for ((k, v) in facts.take(8)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Text(k, color = TextDim, fontSize = 12.sp, modifier = Modifier.weight(0.42f))
                    Text(v, color = TextMain, fontSize = 12.sp, modifier = Modifier.weight(0.58f))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Fly to", primary = true) { engine.flyToSelected() }
        }
    }
}

/** Simulation time: date, speed, pause, real-time. */
@Composable
fun TimeBar(engine: Engine, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(PanelBg, RoundedCornerShape(20.dp))
            .border(1.dp, PanelBorder, RoundedCornerShape(20.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimeButton("⏪") { engine.slower() }
        TimeButton(if (Hud.rateText == "Paused") "▶" else "⏸") { engine.togglePause() }
        TimeButton("⏩") { engine.faster() }
        Column(Modifier.padding(horizontal = 6.dp)) {
            Text(Hud.dateText, color = TextMain, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text(Hud.rateText, color = TextDim, fontSize = 10.sp)
        }
        TimeButton("Now") { engine.timeNow() }
    }
}

@Composable
private fun TimeButton(text: String, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier.clickable { onClick() }.padding(horizontal = 8.dp, vertical = 8.dp),
        color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
    )
}
