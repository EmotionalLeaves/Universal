package com.emotionalleaves.universe.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * Loads textures from the APK assets (or downloaded packs) without stalling the frame:
 * bitmaps decode on a worker thread, upload happens on the GL thread in [pump].
 * [get] returns 0 until the texture is ready, so callers fall back to procedural colours.
 */
class Textures(private val context: Context) {
    private val ids = HashMap<String, Int>()
    private val requested = ConcurrentHashMap.newKeySet<String>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val ready = ConcurrentLinkedQueue<Pair<String, Bitmap>>()
    private val worker = Executors.newSingleThreadExecutor()

    fun get(path: String): Int {
        ids[path]?.let { return it }
        if (path in missing) return 0
        if (requested.add(path)) {
            worker.execute {
                val bmp = decode(path)
                if (bmp != null) ready.add(path to bmp) else missing.add(path)
            }
        }
        return 0
    }

    fun exists(path: String): Boolean = path !in missing

    /** Uploads at most one decoded bitmap per frame. Call on the GL thread. */
    fun pump() {
        val (path, bmp) = ready.poll() ?: return
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        bmp.recycle()
        ids[path] = tex[0]
    }

    /** Forget GL ids after the context is lost (they are invalid). */
    fun contextLost() {
        ids.clear()
        requested.clear()
        ready.clear()
    }

    private fun decode(path: String): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val packFile = File(context.filesDir, "packs/$path")
        if (packFile.exists()) {
            BitmapFactory.decodeFile(packFile.absolutePath, opts)
        } else {
            context.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
        }
    } catch (e: Exception) {
        Log.i("Textures", "No texture $path: ${e.message}")
        null
    }
}
