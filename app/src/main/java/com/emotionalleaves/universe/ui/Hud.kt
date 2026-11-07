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

    fun post(block: () -> Unit) {
        main.post(block)
    }
}
