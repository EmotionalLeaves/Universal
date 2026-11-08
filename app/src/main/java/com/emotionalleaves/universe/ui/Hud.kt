package com.emotionalleaves.universe.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Values shown on screen. Written from the GL thread through [post], read by Compose. */
object Hud {
    private val main = Handler(Looper.getMainLooper())

    var fps by mutableStateOf(0)
    var frameMs by mutableStateOf(0f)
    var distanceText by mutableStateOf("")
    var focusName by mutableStateOf("")
    /** True while the camera is flying somewhere (the info card shrinks out of the way). */
    var flying by mutableStateOf(false)
    /** Bumped when you start dragging or pinching the view (the info card shrinks out of the way). */
    var dragTick by mutableStateOf(0)
    /** Free flight state for the controls: on/off, speed and what is nearest. */
    var freeFlight by mutableStateOf(false)
    var speedText by mutableStateOf("")
    var nearestText by mutableStateOf("")
    var warpText by mutableStateOf("×1")
    /** True when zoomed far out while orbiting (offer free flight). */
    var suggestFree by mutableStateOf(false)
    /** Name of the last object orbited, shown as "Back to …" when drifting in empty space. */
    var lastName by mutableStateOf("")
    var altitudeText by mutableStateOf("")

    var selectedName by mutableStateOf("")
    var selectedSubtitle by mutableStateOf("")
    var selectedSource by mutableStateOf("")
    var selectedDistance by mutableStateOf("")
    var selectedFacts by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var selectedCanLand by mutableStateOf(false)
    var selectedLight by mutableStateOf("")
    /** Short description of the selected object, and where it comes from. */
    var selectedAbout by mutableStateOf("")
    /** Kind label for the chip ("Star", "Moon"…), the vivid description, and two headline numbers. */
    var selectedKind by mutableStateOf("")
    var selectedStory by mutableStateOf("")
    var selectedStats by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var selectedAboutCredit by mutableStateOf("")
    var loading by mutableStateOf("")

    var scaleBarText by mutableStateOf("")
    var scaleBarFraction by mutableStateOf(0.25f)
    var zoomFraction by mutableStateOf(0.5f)
    var sliderDragging by mutableStateOf(false)
    var flying by mutableStateOf(false)
    var dateText by mutableStateOf("")
    var breadcrumb by mutableStateOf("")
    var rateText by mutableStateOf("")

    fun post(block: () -> Unit) {
        main.post(block)
    }
}
