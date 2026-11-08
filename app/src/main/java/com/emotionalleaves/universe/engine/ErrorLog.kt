package com.emotionalleaves.universe.engine

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Non-fatal problems (a layer that failed to draw, a catalogue that failed to load). The app keeps
 * running; the newest report can be copied from Settings and pasted to whoever is fixing the app.
 */
object ErrorLog {
    private var file: File? = null
    private val seen = HashSet<String>()

    fun init(context: Context) {
        file = File(context.filesDir, "last_problem.txt")
    }

    @Synchronized
    fun record(where: String, e: Throwable) {
        val key = where + (e.javaClass.name) + (e.message ?: "")
        if (!seen.add(key)) return
        try { android.util.Log.w("UniverseProblem", "$where: $e") } catch (_: Throwable) {}
        val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        try {
            file?.writeText("Universe Explorer problem in $where at $time\n\n$trace")
        } catch (_: Exception) {
        }
    }

    fun read(): String? = try {
        file?.takeIf { it.exists() }?.readText()
    } catch (_: Exception) {
        null
    }

    fun clear() {
        file?.delete()
        synchronized(this) { seen.clear() }
    }
}
