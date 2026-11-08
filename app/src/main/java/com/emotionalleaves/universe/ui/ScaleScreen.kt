package com.emotionalleaves.universe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.engine.Engine
import com.emotionalleaves.universe.engine.ScaleRefs
import java.util.Locale
import kotlin.math.log10

/** What the sizes screen highlights: (name, width in metres, id) of the thing you were looking at. */
object ScaleUi {
    var focus by mutableStateOf<Triple<String, Double, String>?>(null)
    fun openFor(f: Triple<String, Double, String>?) { focus = f }
}

private fun ratio(k: Double): String = when {
    k >= 1e6 -> Units.bigCount(k)
    k >= 10 -> String.format(Locale.US, "%,.0f", k)
    else -> String.format(Locale.US, "%.1f", k).removeSuffix(".0")
}

/**
 * Sizes compared: a ladder from a person to the observable universe, every rung a familiar thing,
 * with what you selected slotted in where it belongs. Bars are on a log scale (each step is ×10).
 */
@Composable
fun ScaleScreen(engine: Engine) {
    val focus = ScaleUi.focus
    Panel("Sizes compared", { Nav.screen = Screen.NONE }) {
        if (focus != null) {
            val (name, m, _) = focus
            Column(Modifier.fillMaxWidth().background(Color(0x14FFB547), RoundedCornerShape(14.dp)).padding(14.dp)) {
                Overline("Selected", color = Accent)
                Text(name, style = Type.Title)
                Text("${Units.formatDistance(m)} across", style = Type.BodyDim)
                Spacer(Modifier.height(8.dp))
                val earth = 12_742_000.0
                val sun = 1_392_700_000.0
                val lines = ArrayList<String>()
                lines.add(ScaleRefs.like(m, focus.third).removePrefix("≈ ").replaceFirstChar { it.uppercase() })
                if (m > earth * 0.02) lines.add(if (m >= earth) "${ratio(m / earth)} Earths wide" else "1/${ratio(earth / m)} of Earth's width")
                if (m > sun * 0.5) lines.add("${ratio(m / sun)} Suns wide")
                if (m > 9e12) lines.add("Light takes ${Units.lightTime(m)} to cross it")
                for (l in lines.distinct()) Text("• $l", style = Type.Body, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.height(14.dp))
        } else {
            Text("From a person to the whole observable universe: 27 powers of ten. Each bar is on a scale where every step is ten times bigger.", style = Type.BodyDim)
            Spacer(Modifier.height(12.dp))
        }
        val rows = ArrayList<Pair<ScaleRefs.Ref, Boolean>>()
        for (r in ScaleRefs.ladder) if (focus == null || r.bodyId != focus.third) rows.add(r to false)
        if (focus != null) rows.add(ScaleRefs.Ref(focus.first, focus.second, "you are here", focus.third) to true)
        rows.sortBy { it.first.metres }
        val lo = log10(1.0)
        val hi = log10(2 * Units.UNIVERSE_RADIUS)
        for ((r, mine) in rows) {
            val f = ((log10(r.metres) - lo) / (hi - lo)).toFloat().coerceIn(0.02f, 1f)
            Column(
                Modifier.fillMaxWidth()
                    .then(if (mine) Modifier.background(Color(0x1FFFB547), RoundedCornerShape(10.dp)) else Modifier)
                    .clickable(enabled = r.bodyId != null && !mine) { Nav.screen = Screen.NONE; engine.goTo(r.bodyId!!) }
                    .padding(horizontal = 8.dp, vertical = 7.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        r.name, color = if (mine) Accent else TextMain, fontSize = 14.sp,
                        fontWeight = if (mine) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Text(Units.formatDistance(r.metres), color = TextDim, fontSize = 12.sp)
                    if (r.bodyId != null && !mine) Glyph(G.CHEVRON_RIGHT, size = 14.dp, color = TextFaint, modifier = Modifier.padding(start = 4.dp))
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(5.dp).background(Color(0x14FFFFFF), RoundedCornerShape(3.dp))) {
                    Box(Modifier.fillMaxWidth(f).height(5.dp).background(if (mine) Accent else Color(0xFF7FA8FF), RoundedCornerShape(3.dp)))
                }
                if (!mine) Text(r.note, color = TextFaint, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
