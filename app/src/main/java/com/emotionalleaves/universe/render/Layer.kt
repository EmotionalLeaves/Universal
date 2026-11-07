package com.emotionalleaves.universe.render

import android.content.Context
import com.emotionalleaves.universe.engine.Body
import com.emotionalleaves.universe.engine.Engine

/** Something that can be tapped: where it is on screen and how important its label is. */
class Pickable(
    val body: Body,
    val x: Float,
    val y: Float,
    val pixelRadius: Float,
    /** Higher wins when labels overlap or taps are ambiguous. */
    val priority: Float,
    val showLabel: Boolean,
    val labelColor: Int = 0xFFE6ECFF.toInt(),
)

/** Shared GL resources handed to every layer. */
class RenderContext(
    val context: Context,
    val engine: Engine,
    val programs: Programs,
    val drawer: BodyDrawer,
    val textures: Textures,
)

/** One part of the universe that knows how to draw itself. */
interface Layer {
    /** Called once the GL context exists (and again if it is recreated). */
    fun init(rc: RenderContext) {}

    /** Prepare for this frame (e.g. pick up background-generated data). Runs before drawing. */
    fun update(frame: Frame, rc: RenderContext) {}

    /** Opaque geometry (writes depth). */
    fun drawOpaque(frame: Frame, rc: RenderContext) {}

    /** Additive glows and points (reads depth). */
    fun drawTransparent(frame: Frame, rc: RenderContext) {}

    /** Tap targets and labels. */
    fun collect(frame: Frame, rc: RenderContext, out: MutableList<Pickable>) {}

    /** Second chance for taps on things not in the pickable list (e.g. any of 100,000 stars). */
    fun pickAt(frame: Frame, rc: RenderContext, x: Float, y: Float, reach: Float): Body? = null
}
