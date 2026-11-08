package com.emotionalleaves.universe.render

import com.emotionalleaves.universe.core.Units
import kotlin.math.log10
import kotlin.math.max

/**
 * The faintest magnitude drawn at full brightness, from how far out you are looking. Grows as you
 * zoom out so distant galaxies show up, but slower beyond ~30 Mpc: there are so many galaxies at
 * those scales that full exposure turned the whole universe into a white ball.
 */
object Exposure {
    fun of(frame: Frame): Float = forDistance(frame.focusDistance)

    fun forDistance(focus: Double): Float {
        val mpc = focus / Units.MPC
        val near = 7.2 + 5 * log10(max(1.0, mpc.coerceAtMost(30.0) / 0.3))
        val far = if (mpc > 30) 1.3 * log10(mpc / 30.0) else 0.0
        return (near + far).toFloat()
    }
}
