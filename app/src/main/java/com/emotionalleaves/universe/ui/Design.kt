package com.emotionalleaves.universe.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------- palette

/** Deep-space glass panels with one warm accent; green = real data, violet = generated. */
val PanelBg = Color(0xE00E121D)
val PanelRaised = Color(0xF0151A28)
val PanelBorder = Color(0x22FFFFFF)
val HairlineColor = Color(0x14FFFFFF)
val TextMain = Color(0xFFF1F4FB)
val TextDim = Color(0xFF9AA4BA)
val TextFaint = Color(0xFF66708A)
val Accent = Color(0xFFFFC46B)
val AccentInk = Color(0xFF1C1405)
val RealGreen = Color(0xFF6ED9A0)
val GenPurple = Color(0xFFB59BFF)
val Danger = Color(0xFFFF8A8A)

/** Colour that marks each kind of object (chips, Explore headers). */
fun kindColor(kind: String): Color = when (kind.lowercase()) {
    "star" -> Color(0xFFFFD27A)
    "planet", "dwarf planet" -> Color(0xFF6CC7E8)
    "moon" -> Color(0xFFC9CED8)
    "galaxy", "galaxy cluster" -> Color(0xFFB59BFF)
    "nebula", "star cluster" -> Color(0xFFFF8FB0)
    "asteroid", "comet" -> Color(0xFFD9B98C)
    "pulsar", "quasar", "neutron star" -> Color(0xFF8FE3FF)
    "black hole", "rogue planet" -> Color(0xFFA48CFF)
    else -> TextDim
}

// ---------------------------------------------------------------------------- type

object Type {
    val Title = TextStyle(color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    val Heading = TextStyle(color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val Body = TextStyle(color = TextMain, fontSize = 14.sp, lineHeight = 20.sp)
    val BodyDim = TextStyle(color = TextDim, fontSize = 13.sp, lineHeight = 18.sp)
    val Caption = TextStyle(color = TextDim, fontSize = 12.sp)
    val Overline = TextStyle(color = TextFaint, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
    val Number = TextStyle(color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
}

fun Modifier.glass(radius: Dp = 16.dp, raised: Boolean = false): Modifier =
    this.background(if (raised) PanelRaised else PanelBg, RoundedCornerShape(radius)).border(1.dp, PanelBorder, RoundedCornerShape(radius))

// ---------------------------------------------------------------------------- icons

/** A small, consistent line-icon set drawn in code (24-unit grid, rounded strokes). */
enum class G {
    SEARCH, HOME, MENU, CLOSE, STAR, PLAY, PAUSE, FWD, REW, COMPASS, DICE, UP, FLY, LAND, CHEVRON_DOWN, CHEVRON_UP,
    CHEVRON_RIGHT, BACK, BOOKMARK, SLIDERS, INFO, HELP, DOWNLOAD, CHART, CLOCK, TARGET,
    SUN, PLANET, MOON, ASTEROID, NEBULA, GALAXY, BLACKHOLE, UNIVERSE, SPARKLE,
}

@Composable
fun Glyph(g: G, modifier: Modifier = Modifier, size: Dp = 20.dp, color: Color = TextMain) {
    Canvas(modifier.size(size)) { drawGlyph(g, color) }
}

private fun DrawScope.drawGlyph(g: G, c: Color) {
    val u = size.minDimension / 24f
    val w = 1.8f * u
    val st = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(c, p(x1, y1), p(x2, y2), w, StrokeCap.Round)
    fun poly(vararg pts: Float, close: Boolean = false, fill: Boolean = false) {
        val path = Path()
        path.moveTo(pts[0] * u, pts[1] * u)
        var i = 2
        while (i < pts.size) { path.lineTo(pts[i] * u, pts[i + 1] * u); i += 2 }
        if (close) path.close()
        if (fill) drawPath(path, c) else drawPath(path, c, style = st)
    }
    fun circle(x: Float, y: Float, r: Float, fill: Boolean = false) =
        if (fill) drawCircle(c, r * u, p(x, y)) else drawCircle(c, r * u, p(x, y), style = st)
    when (g) {
        G.SEARCH -> { circle(10.5f, 10.5f, 6.5f); line(15.5f, 15.5f, 20f, 20f) }
        G.HOME -> { poly(4f, 11.5f, 12f, 4.5f, 20f, 11.5f); poly(6.5f, 10f, 6.5f, 19.5f, 17.5f, 19.5f, 17.5f, 10f); poly(10f, 19.5f, 10f, 14.5f, 14f, 14.5f, 14f, 19.5f) }
        G.MENU -> { line(4f, 7f, 20f, 7f); line(4f, 12f, 20f, 12f); line(4f, 17f, 20f, 17f) }
        G.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
        G.STAR, G.SPARKLE -> {
            val pts = FloatArray(20)
            for (k in 0 until 10) {
                val r = if (k % 2 == 0) 8.5f else 3.6f
                val a = Math.toRadians(-90.0 + k * 36.0)
                pts[k * 2] = 12f + r * kotlin.math.cos(a).toFloat(); pts[k * 2 + 1] = 12.6f + r * kotlin.math.sin(a).toFloat()
            }
            poly(*pts, close = true, fill = g == G.SPARKLE)
        }
        G.PLAY -> poly(8f, 5f, 19f, 12f, 8f, 19f, close = true, fill = true)
        G.PAUSE -> { drawRoundRect(c, p(6.5f, 5f), Size(4f * u, 14f * u), androidx.compose.ui.geometry.CornerRadius(u)); drawRoundRect(c, p(13.5f, 5f), Size(4f * u, 14f * u), androidx.compose.ui.geometry.CornerRadius(u)) }
        G.FWD -> { poly(3.5f, 6f, 11.5f, 12f, 3.5f, 18f, close = true, fill = true); poly(12f, 6f, 20.5f, 12f, 12f, 18f, close = true, fill = true) }
        G.REW -> { poly(20.5f, 6f, 12.5f, 12f, 20.5f, 18f, close = true, fill = true); poly(12f, 6f, 3.5f, 12f, 12f, 18f, close = true, fill = true) }
        G.COMPASS -> { circle(12f, 12f, 9f); poly(15.5f, 8.5f, 13.2f, 13.2f, 8.5f, 15.5f, 10.8f, 10.8f, close = true, fill = true) }
        G.DICE -> {
            drawRoundRect(c, p(4f, 4f), Size(16f * u, 16f * u), androidx.compose.ui.geometry.CornerRadius(3.5f * u), style = st)
            circle(8.7f, 8.7f, 1.4f, true); circle(12f, 12f, 1.4f, true); circle(15.3f, 15.3f, 1.4f, true)
        }
        G.UP -> { line(12f, 19f, 12f, 5.5f); poly(6.5f, 11f, 12f, 5.5f, 17.5f, 11f) }
        G.FLY -> poly(4f, 11f, 20f, 4f, 13f, 20f, 11f, 13f, close = true, fill = true)
        G.LAND -> { line(12f, 4f, 12f, 15f); poly(7f, 10f, 12f, 15f, 17f, 10f); line(4.5f, 20f, 19.5f, 20f) }
        G.CHEVRON_DOWN -> poly(6f, 9.5f, 12f, 15.5f, 18f, 9.5f)
        G.CHEVRON_UP -> poly(6f, 14.5f, 12f, 8.5f, 18f, 14.5f)
        G.CHEVRON_RIGHT -> poly(9.5f, 6f, 15.5f, 12f, 9.5f, 18f)
        G.BACK -> { line(19.5f, 12f, 5f, 12f); poly(11f, 6f, 5f, 12f, 11f, 18f) }
        G.BOOKMARK -> poly(6.5f, 4f, 17.5f, 4f, 17.5f, 20f, 12f, 15.5f, 6.5f, 20f, close = true)
        G.SLIDERS -> {
            line(4f, 7f, 20f, 7f); line(4f, 12f, 20f, 12f); line(4f, 17f, 20f, 17f)
            circle(9f, 7f, 2f, true); circle(15f, 12f, 2f, true); circle(7.5f, 17f, 2f, true)
        }
        G.INFO -> { circle(12f, 12f, 9f); line(12f, 11f, 12f, 16.5f); circle(12f, 7.8f, 1.1f, true) }
        G.HELP -> {
            circle(12f, 12f, 9f)
            drawArc(c, 180f, 250f, false, p(9.2f, 6.6f), Size(5.6f * u, 5.6f * u), style = st)
            line(12f, 12.4f, 12f, 13.6f); circle(12f, 16.8f, 1.1f, true)
        }
        G.DOWNLOAD -> { line(12f, 4f, 12f, 14.5f); poly(7.5f, 10f, 12f, 14.5f, 16.5f, 10f); poly(4.5f, 15f, 4.5f, 19.5f, 19.5f, 19.5f, 19.5f, 15f) }
        G.CHART -> { poly(5f, 20f, 5f, 13f, 8.5f, 13f, 8.5f, 20f); poly(10.3f, 20f, 10.3f, 8f, 13.7f, 8f, 13.7f, 20f); poly(15.5f, 20f, 15.5f, 4f, 19f, 4f, 19f, 20f) }
        G.CLOCK -> { circle(12f, 12f, 9f); poly(12f, 7f, 12f, 12f, 15.5f, 14f) }
        G.TARGET -> { circle(12f, 12f, 6f); line(12f, 2.5f, 12f, 6f); line(12f, 18f, 12f, 21.5f); line(2.5f, 12f, 6f, 12f); line(18f, 12f, 21.5f, 12f); circle(12f, 12f, 1.5f, true) }
        G.SUN -> {
            circle(12f, 12f, 4.2f)
            for (k in 0 until 8) {
                val a = Math.toRadians(k * 45.0)
                val cx = kotlin.math.cos(a).toFloat(); val sy = kotlin.math.sin(a).toFloat()
                line(12f + 7f * cx, 12f + 7f * sy, 12f + 9.5f * cx, 12f + 9.5f * sy)
            }
        }
        G.PLANET -> {
            circle(12f, 12f, 5.5f)
            rotate(-25f, p(12f, 12f)) { drawOval(c, p(2.5f, 9.5f), Size(19f * u, 5f * u), style = st) }
        }
        G.MOON -> {
            val path = Path()
            path.addArc(androidx.compose.ui.geometry.Rect(p(3.5f, 3.5f), Size(17f * u, 17f * u)), 70f, 220f)
            path.addArc(androidx.compose.ui.geometry.Rect(p(9f, 2.5f), Size(13f * u, 13f * u)), 230f, -200f)
            drawPath(path, c, style = st)
        }
        G.ASTEROID -> poly(7f, 5f, 15f, 4f, 20f, 9f, 18.5f, 16f, 13f, 20f, 6f, 18f, 4f, 11f, close = true)
        G.NEBULA -> {
            drawArc(c, 150f, 200f, false, p(3f, 9f), Size(9f * u, 9f * u), style = st)
            drawArc(c, 200f, 170f, false, p(8f, 5f), Size(10f * u, 10f * u), style = st)
            drawArc(c, 250f, 190f, false, p(12f, 9f), Size(9f * u, 9f * u), style = st)
            line(7.5f, 18f, 16.5f, 18f)
        }
        G.GALAXY -> {
            circle(12f, 12f, 1.6f, true)
            drawArc(c, 180f, 220f, false, p(5f, 6f), Size(14f * u, 12f * u), style = st)
            drawArc(c, 0f, 220f, false, p(5f, 6f), Size(14f * u, 12f * u), style = st)
        }
        G.BLACKHOLE -> {
            circle(12f, 12f, 3.6f, true)
            rotate(-15f, p(12f, 12f)) { drawOval(c, p(2f, 9f), Size(20f * u, 6f * u), style = st) }
        }
        G.UNIVERSE -> { circle(12f, 12f, 9f); circle(12f, 12f, 5f); circle(12f, 12f, 1.4f, true) }
    }
}

// ---------------------------------------------------------------------------- components

/** Round glass button with an icon (top bar, card actions). */
@Composable
fun IconButton(g: G, modifier: Modifier = Modifier, size: Dp = 44.dp, tint: Color = TextMain, active: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier
            .size(size)
            .background(if (active) Accent else PanelBg, CircleShape)
            .border(1.dp, if (active) Color.Transparent else PanelBorder, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Glyph(g, size = size * 0.45f, color = if (active) AccentInk else tint) }
}

/** Main buttons: filled accent (primary) or glass (secondary), optional leading icon. */
@Composable
fun Button(text: String, modifier: Modifier = Modifier, glyph: G? = null, primary: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier
            .height(40.dp)
            .background(if (primary) Accent else PanelRaised, RoundedCornerShape(12.dp))
            .border(1.dp, if (primary) Color.Transparent else PanelBorder, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (glyph != null) Glyph(glyph, size = 17.dp, color = if (primary) AccentInk else TextMain)
        Text(text, color = if (primary) AccentInk else TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Kept for older screens: a text-only [Button]. */
@Composable
fun PillButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) =
    Button(text, modifier, null, primary, onClick)

/** Small rounded label: outlined (default) or tinted. */
@Composable
fun Chip(text: String, color: Color, filled: Boolean = false) {
    Text(
        text.uppercase(),
        modifier = Modifier
            .background(if (filled) color.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = if (filled) 0f else 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, maxLines = 1,
    )
}

@Composable
fun Overline(text: String, modifier: Modifier = Modifier, color: Color = TextFaint) {
    Text(text.uppercase(), modifier = modifier, style = Type.Overline.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(HairlineColor))
}

/** A labelled number in the info card's headline row. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(Color(0x0DFFFFFF), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 7.dp)) {
        Overline(label)
        Text(value, style = Type.Number.copy(fontSize = 13.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A tappable list row: icon in a tinted circle, title, detail, chevron. */
@Composable
fun ListRow(title: String, detail: String = "", glyph: G? = null, tint: Color = TextDim, trailing: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            Box(Modifier.size(34.dp).background(tint.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                Glyph(glyph, size = 18.dp, color = tint)
            }
            androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.Body.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, style = Type.Caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Text(trailing, style = Type.Caption.copy(color = Accent, fontWeight = FontWeight.SemiBold))
        else Glyph(G.CHEVRON_RIGHT, size = 16.dp, color = TextFaint)
    }
}
