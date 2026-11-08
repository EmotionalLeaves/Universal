package com.emotionalleaves.universe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.emotionalleaves.universe.engine.Engine


@Composable
fun TopStatus(engine: Engine, modifier: Modifier = Modifier) {
    if (Hud.focusName.isEmpty()) return
    Column(modifier) {
        Column(
            Modifier.glass(14.dp)
                .clickable(enabled = Hud.freeFlight && Hud.canVisitNearest) { engine.travelToNearest() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (Hud.breadcrumb.isNotEmpty()) Overline(Hud.breadcrumb)
            Text(Hud.focusName, style = Type.Heading.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (Hud.freeFlight) {
                Text(Hud.nearestText, style = Type.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Hud.distanceText, style = Type.Caption.copy(fontFeatureSettings = "tnum"), maxLines = 1)
                if (Hud.altitudeText.isNotEmpty()) {
                    Text("  ·  ", style = Type.Caption.copy(color = TextFaint))
                    Text(Hud.altitudeText, style = Type.Caption.copy(fontFeatureSettings = "tnum"), maxLines = 1)
                }
            }
        }
        // Free flight: one tap travels to the nearest thing (the line above says what and how far).
        if (Hud.freeFlight && Hud.canVisitNearest) {
            Button("Go to nearest", Modifier.padding(top = 6.dp), glyph = G.FLY, primary = true) { engine.travelToNearest() }
        }
        if (Hud.lastName.isNotEmpty()) {
            Button("Back to ${Hud.lastName}", Modifier.padding(top = 6.dp), glyph = G.TARGET, primary = true) { engine.recenter() }
        }
    }
}

@Composable
fun LoadingChip(modifier: Modifier = Modifier) {
    if (Hud.loading.isEmpty()) return
    Row(
        modifier.glass(10.dp).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(Accent, CircleShape))
        Spacer(Modifier.width(7.dp))
        Text(Hud.loading, style = Type.Caption.copy(color = TextMain, fontSize = 11.5.sp))
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
 * observable universe. Drag it to cross the universe in one swipe. Labels on the left jump
 * straight to a scale (Planet, Solar system ...).
 */
@Composable
fun ZoomSlider(engine: Engine, modifier: Modifier = Modifier) {
    var trackH by remember { mutableStateOf(1) }
    val density = LocalDensity.current
    // Shorter on small or landscape screens so it never runs into the top bar or the info card.
    val trackDp = (LocalConfiguration.current.screenHeightDp * 0.42f).coerceIn(160f, 300f).dp
    // Recomputed each time: the zoom range grows when "beyond the observable universe" is on.
    val presets = engine.zoomPresets.filter { it.second <= engine.camera.maxDistance() }.map { (n, alt) -> n to engine.zoomFractionFor(alt) }
    Row(modifier.height(trackDp)) {
        // Scale labels, each placed at its height on the track.
        // The whole label column is a tap target (nearest label wins), so a slightly-off tap
        // zooms instead of falling through and selecting a star behind the label.
        Box(
            Modifier.width(92.dp).height(trackDp).pointerInput(presets) {
                detectTapGestures { o ->
                    val f = 1f - (o.y / trackH).coerceIn(0f, 1f)
                    val best = presets.minByOrNull { kotlin.math.abs(it.second - f) } ?: return@detectTapGestures
                    Hud.zoomFraction = best.second; engine.setZoomFraction(best.second)
                }
            },
        ) {
            for ((name, f) in presets) {
                val near = kotlin.math.abs(Hud.zoomFraction - f) < 0.04f
                val y = with(density) { ((1f - f) * trackH).toDp() } - 10.dp
                Text(
                    name,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = y)
                        .background(if (near) Accent.copy(alpha = 0.18f) else Color(0x8C0E121D), RoundedCornerShape(8.dp))
                        .clickable { Hud.zoomFraction = f; engine.setZoomFraction(f) }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = if (near) Accent else TextDim,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
        }
        Box(
            Modifier
                .width(40.dp)
                .height(trackDp)
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
                    .height(trackDp)
                    .background(Color(0x55FFFFFF), RoundedCornerShape(2.dp)),
            )
            for ((_, f) in presets) {
                val ty = with(density) { ((1f - f) * trackH).toDp() } - 1.dp
                Box(Modifier.offset(y = ty).width(12.dp).height(2.dp).background(Color(0x88FFFFFF)))
            }
            val y = with(density) { ((1f - Hud.zoomFraction) * trackH).toDp() } - 11.dp
            Box(
                Modifier
                    .offset(y = y)
                    .size(22.dp)
                    .background(Accent, CircleShape)
                    .border(2.dp, Color(0xAA000000), CircleShape),
            )
        }
    }
}

@Composable
fun HomeButton(engine: Engine, modifier: Modifier = Modifier) {
    IconButton(G.HOME, modifier) { engine.goHome() }
}

@Composable
fun SearchButton(modifier: Modifier = Modifier) {
    IconButton(G.SEARCH, modifier) { Nav.screen = Screen.SEARCH }
}

@Composable
fun ExploreButton(modifier: Modifier = Modifier) {
    Button("Explore", modifier, glyph = G.COMPASS, primary = true) { Nav.screen = Screen.EXPLORE }
}

@Composable
fun SourceBadge(source: String) {
    if (source.isEmpty()) return
    Chip(source, if (source == "Real") RealGreen else GenPurple)
}

/**
 * The selected object's card: kind and source chips, name, three headline numbers, a short
 * description, then (expanded) the Wikipedia summary and every fact, and the actions.
 */
@Composable
fun InfoCard(engine: Engine, modifier: Modifier = Modifier) {
    if (Hud.selectedName.isEmpty()) return
    // Each new selection starts with the normal card.
    var expanded by remember(Hud.selectedName) { mutableStateOf(false) }
    var mini by remember(Hud.selectedName) { mutableStateOf(false) }
    // Get out of the way while you fly, roam freely or move the view; tap to bring it back.
    val firstTick = remember(Hud.selectedName) { Hud.dragTick }
    // After a "Fly to" the card comes back on arrival (that is when you want the facts);
    // after a drag or free flight it stays small until you ask for it.
    var restoreOnArrival by remember(Hud.selectedName) { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(Hud.flying, Hud.freeFlight) {
        if (Hud.flying || Hud.freeFlight) { mini = true; expanded = false }
        else if (mini && restoreOnArrival) { mini = false; restoreOnArrival = false }
    }
    androidx.compose.runtime.LaunchedEffect(Hud.dragTick) {
        if (Hud.dragTick != firstTick) { mini = true; expanded = false; restoreOnArrival = false }
    }
    if (mini) {
        Row(
            modifier.widthIn(max = 540.dp).fillMaxWidth().glass(18.dp).clickable { mini = false }
                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (Hud.selectedKind.isNotEmpty()) Chip(Hud.selectedKind, kindColor(Hud.selectedKind), filled = true)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(Hud.selectedName, style = Type.Body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (Hud.selectedDistance.isNotEmpty()) Text("${Hud.selectedDistance} away", style = Type.Caption.copy(fontSize = 11.sp), maxLines = 1)
            }
            if (!Hud.flying) IconButton(G.FLY, size = 36.dp, label = "Fly to") { restoreOnArrival = true; engine.flyToSelected() }
            Spacer(Modifier.width(6.dp))
            IconButton(G.CHEVRON_UP, size = 36.dp, label = "Show card") { mini = false }
            Spacer(Modifier.width(6.dp))
            IconButton(G.CLOSE, size = 36.dp, tint = TextDim) { engine.clearSelection() }
        }
        return
    }
    Column(
        modifier
            .widthIn(max = 540.dp)
            .fillMaxWidth()
            .glass(20.dp)
            .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (Hud.selectedKind.isNotEmpty()) Chip(Hud.selectedKind, kindColor(Hud.selectedKind), filled = true)
                SourceBadge(Hud.selectedSource)
            }
            IconButton(G.CHEVRON_DOWN, size = 32.dp, tint = TextDim, label = "Minimize card") { mini = true; expanded = false }
            Spacer(Modifier.width(4.dp))
            IconButton(G.CLOSE, size = 32.dp, tint = TextDim) { engine.clearSelection() }
        }
        Column(Modifier.padding(end = 6.dp)) {
            Text(
                Hud.selectedName, style = Type.Title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (Hud.selectedSubtitle.isNotEmpty()) Text(Hud.selectedSubtitle, style = Type.BodyDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Crowded spot: the other things under your finger, one tap away.
            if (Hud.choices.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Also here", style = Type.Caption.copy(fontSize = 11.sp))
                    for ((name, id) in Hud.choices) {
                        Text(
                            name, maxLines = 1, fontSize = 12.sp, color = TextMain,
                            modifier = Modifier.background(PanelRaised, RoundedCornerShape(10.dp))
                                .clickable { engine.selectId(id) }.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
            // Headline numbers
            val tiles = buildList {
                if (Hud.selectedDistance.isNotEmpty()) add("From you" to Hud.selectedDistance)
                addAll(Hud.selectedStats)
            }.take(3)
            if (tiles.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((k, v) in tiles) StatTile(k, v, Modifier.weight(1f))
                }
            }
            val maxH = (LocalConfiguration.current.screenHeightDp * (if (expanded) 0.34f else 0.14f)).dp
            Column(Modifier.padding(top = 10.dp).heightIn(max = maxH).verticalScroll(rememberScrollState())) {
                if (Hud.selectedStory.isNotEmpty()) {
                    Text(Hud.selectedStory, style = Type.Body.copy(fontSize = 13.5.sp, lineHeight = 19.sp, color = Color(0xFFDCE2EE)),
                        maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                }
                if (expanded) {
                    if (Hud.selectedAbout.isNotEmpty()) {
                        Overline("About", Modifier.padding(top = 14.dp, bottom = 4.dp))
                        Text(Hud.selectedAbout, style = Type.BodyDim.copy(color = Color(0xFFC4CBDA)))
                        if (Hud.selectedAboutCredit.isNotEmpty()) Text(Hud.selectedAboutCredit, style = Type.Caption.copy(fontSize = 10.sp, color = TextFaint), modifier = Modifier.padding(top = 4.dp))
                    }
                    if (Hud.selectedFacts.isNotEmpty()) {
                        Overline("Facts", Modifier.padding(top = 14.dp, bottom = 2.dp))
                        for ((k, v) in Hud.selectedFacts) {
                            Hairline()
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(k, style = Type.Caption, modifier = Modifier.weight(0.42f))
                                Text(v, style = Type.Caption.copy(color = TextMain), modifier = Modifier.weight(0.58f))
                            }
                        }
                    }
                }
            }
            if (Hud.selectedStory.isNotEmpty() || Hud.selectedFacts.isNotEmpty()) {
                Row(
                    Modifier.clickable { expanded = !expanded }.padding(top = 6.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (expanded) "Show less" else "Read more & all facts", style = Type.Caption.copy(color = Accent, fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.width(4.dp))
                    Glyph(if (expanded) G.CHEVRON_UP else G.CHEVRON_DOWN, size = 14.dp, color = Accent)
                }
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button("Fly to", glyph = G.FLY, primary = true) { mini = true; expanded = false; restoreOnArrival = true; engine.flyToSelected() }
                Spacer(Modifier.weight(1f))
                IconButton(G.UP, size = 40.dp) { engine.goUp() }
                IconButton(G.BOOKMARK, size = 40.dp) {
                    val n = engine.addBookmark()
                    BookmarksUi.toast = "Saved \u201c$n\u201d to bookmarks"
                }
            }
            if (BookmarksUi.toast.isNotEmpty()) {
                Text(BookmarksUi.toast, style = Type.Caption.copy(color = RealGreen), modifier = Modifier.padding(top = 6.dp))
                androidx.compose.runtime.LaunchedEffect(BookmarksUi.toast) {
                    kotlinx.coroutines.delay(2500)
                    BookmarksUi.toast = ""
                }
            }
        }
    }
}

/** Simulation time: slower, pause, faster, date and speed, back to now. */
@Composable
fun TimeBar(engine: Engine, modifier: Modifier = Modifier) {
    Row(
        modifier.glass(22.dp).padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimeButton(G.REW) { engine.slower() }
        TimeButton(if (Hud.rateText == "Paused") G.PLAY else G.PAUSE) { engine.togglePause() }
        TimeButton(G.FWD) { engine.faster() }
        Column(Modifier.padding(horizontal = 6.dp)) {
            Text(Hud.dateText, style = Type.Number.copy(fontSize = 12.5.sp))
            Text(Hud.rateText, style = Type.Caption.copy(fontSize = 10.5.sp))
        }
        Row(
            Modifier.clickable { engine.timeNow() }.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(G.CLOCK, size = 15.dp, color = Accent)
            Spacer(Modifier.width(4.dp))
            Text("Now", style = Type.Caption.copy(color = Accent, fontWeight = FontWeight.SemiBold))
        }
    }
}

@Composable
private fun TimeButton(g: G, onClick: () -> Unit) {
    val desc = when (g) { G.REW -> "Slower"; G.FWD -> "Faster"; G.PLAY -> "Play"; else -> "Pause" }
    Box(Modifier.size(36.dp).semantics { contentDescription = desc }.clickable { onClick() }, contentAlignment = Alignment.Center) { Glyph(g, size = 15.dp) }
}


/** Toggles free flight (look around and fly anywhere) vs orbiting an object. */
@Composable
fun FreeFlightButton(engine: Engine, modifier: Modifier = Modifier) {
    IconButton(G.FLY, modifier, active = Hud.freeFlight) { engine.setFreeFlight(!Hud.freeFlight); Hud.freeFlight = !Hud.freeFlight }
}

/** Free-flight controls: hold to fly forward or back, warp steps, speed readout. */
@Composable
fun FlightPad(engine: Engine, modifier: Modifier = Modifier) {
    Column(
        modifier.glass(22.dp).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(Hud.warpText, style = Type.Caption.copy(color = Accent, fontWeight = FontWeight.Bold),
            modifier = Modifier.background(Accent.copy(alpha = 0.14f), RoundedCornerShape(8.dp)).clickable {
                engine.warp = (engine.warp + 1) % engine.warpFactors.size
            }.padding(horizontal = 10.dp, vertical = 6.dp))
        HoldButton(G.UP, "Forward") { engine.thrust = if (it) 1 else 0 }
        HoldButton(G.CHEVRON_DOWN, "Back") { engine.thrust = if (it) -1 else 0 }
        Text(Hud.speedText, style = Type.Caption.copy(fontSize = 10.sp, fontFeatureSettings = "tnum"), maxLines = 1)
        Text("Exit", style = Type.Caption.copy(color = TextDim), modifier = Modifier.clickable { engine.setFreeFlight(false); Hud.freeFlight = false }.padding(4.dp))
    }
}

@Composable
private fun HoldButton(g: G, label: String, onHold: (Boolean) -> Unit) {
    var held by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(58.dp)
            .background(if (held) Accent else PanelRaised, CircleShape)
            .border(1.dp, PanelBorder, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    held = true; onHold(true)
                    tryAwaitRelease()
                    held = false; onHold(false)
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Glyph(g, size = 20.dp, color = if (held) AccentInk else TextMain)
            Text(label, style = Type.Caption.copy(fontSize = 9.sp, color = if (held) AccentInk else TextDim))
        }
    }
}

/** Shown when you are zoomed far out while orbiting: suggests free flight. */
@Composable
fun FreeFlightHint(engine: Engine, modifier: Modifier = Modifier) {
    if (Hud.freeFlight || !Hud.suggestFree) return
    Button("Fly freely here", modifier, glyph = G.FLY) { engine.setFreeFlight(true); Hud.freeFlight = true }
}
