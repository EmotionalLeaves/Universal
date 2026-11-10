package com.emotionalleaves.universe.render

import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.engine.ChunkIndex
import com.emotionalleaves.universe.engine.ErrorLog

/**
 * Gets one big point set onto the GPU without freezing the picture: the chunk index (see
 * [ChunkIndex]) is built on a background thread, then the points are uploaded a slice per frame.
 * The previous batch keeps being drawn until the new one is complete.
 */
class ChunkedLoader<K : Any>(private val leaf: Int = 4096, private val perFrame: Int = 120_000) {
    var batch: PointBatch? = null
        private set
    /** What [batch] shows. */
    var shown: K? = null
        private set
    private var working: K? = null
    @Volatile private var ready: Pair<K, ChunkIndex?>? = null
    private var staged: PointBatch.Staged? = null

    /** The GL context was recreated: forget everything (old GL objects are gone). */
    fun reset() { batch = null; shown = null; working = null; ready = null; staged = null }

    /** Call every frame with the set that should be shown (or null for none). */
    fun update(
        want: K?, count: Int, anchor: UPos, unit: Double,
        x: (Int) -> Float, y: (Int) -> Float, z: (Int) -> Float, mag: (Int) -> Float,
        fill: (i: Int, out: FloatArray, at: Int) -> Unit,
    ) {
        if (want == null) {
            if (shown != null || working != null) {
                staged?.cancel(); staged = null
                batch?.release(); batch = null
                shown = null; working = null; ready = null
            }
            return
        }
        if (want !== shown && want !== working) {
            staged?.cancel(); staged = null; ready = null
            working = want
            worker.execute {
                val idx = try { ChunkIndex.build(count, leaf, x, y, z, mag) } catch (e: Throwable) { ErrorLog.record("indexing points", e); null }
                ready = want to idx
            }
        }
        val r = ready
        if (r != null && r.first === working && staged == null) {
            staged = PointBatch.Staged(anchor, unit, count, r.second, fill = fill)
            ready = null
        }
        staged?.step(perFrame)?.let {
            batch?.release()
            batch = it
            shown = working; working = null; staged = null
        }
    }

    companion object {
        val worker: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "point-index").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
        }
    }
}
