package com.emotionalleaves.universe

import android.app.Application

class UniverseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
