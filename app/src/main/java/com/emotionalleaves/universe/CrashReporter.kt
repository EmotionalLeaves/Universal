package com.emotionalleaves.universe

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Catches any crash, saves the full error to a file, and lets the app show it
 * on the next launch so it can be copied from the phone and pasted to an AI.
 */
object CrashReporter {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                file(appContext).writeText(buildReport(appContext, thread, error))
            } catch (_: Throwable) {
                // Never let the crash reporter itself crash.
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    fun read(context: Context): String? {
        val f = file(context)
        return if (f.exists()) f.readText().takeIf { it.isNotBlank() } else null
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun buildReport(context: Context, thread: Thread, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        return buildString {
            appendLine("Universe Explorer crash report")
            appendLine("App version: ${AppInfo.version(context)}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Time: $time")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(trace)
        }
    }
}
