package com.emotionalleaves.universe.engine

import android.content.Context

/** User settings, saved between launches. Read from any thread; write from the UI. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Volatile var showOrbits = prefs.getBoolean("orbits", true)
        set(v) { field = v; prefs.edit().putBoolean("orbits", v).apply() }
    @Volatile var showLabels = prefs.getBoolean("labels", true)
        set(v) { field = v; prefs.edit().putBoolean("labels", v).apply() }
    @Volatile var showGenerated = prefs.getBoolean("generated", true)
        set(v) { field = v; prefs.edit().putBoolean("generated", v).apply() }
    /** Show a speculative, generated universe beyond the edge of the observable one. */
    @Volatile var showBeyond = prefs.getBoolean("beyond", true)
        set(v) { field = v; prefs.edit().putBoolean("beyond", v).apply() }
    @Volatile var showConstellations = prefs.getBoolean("constellations", false)
        set(v) { field = v; prefs.edit().putBoolean("constellations", v).apply() }
    @Volatile var showTestObjects = prefs.getBoolean("test", false)
        set(v) { field = v; prefs.edit().putBoolean("test", v).apply() }
    @Volatile var showStats = prefs.getBoolean("stats", false)
        set(v) { field = v; prefs.edit().putBoolean("stats", v).apply() }
    /** 0 = Low, 1 = Medium, 2 = High. */
    @Volatile var quality = prefs.getInt("quality", 1)
        set(v) { field = v; prefs.edit().putInt("quality", v).apply() }
    @Volatile var batterySaver = prefs.getBoolean("battery", false)
        set(v) { field = v; prefs.edit().putBoolean("battery", v).apply() }
    /** Extra star brightness in magnitudes (-2..+3). */
    @Volatile var starBrightness = prefs.getFloat("starBright", 0.5f)
        set(v) { field = v; prefs.edit().putFloat("starBright", v).apply() }
    /** Look names up in SIMBAD, NED and JPL when the app's own catalogues don't have them. */
    @Volatile var onlineSearch = prefs.getBoolean("onlineSearch", true)
        set(v) { field = v; prefs.edit().putBoolean("onlineSearch", v).apply() }
    /** Stream real Gaia DR3 stars around you while you fly through the Milky Way. */
    @Volatile var gaiaStream = prefs.getBoolean("gaiaStream", true)
        set(v) { field = v; prefs.edit().putBoolean("gaiaStream", v).apply() }
    /** Stream real GLADE+ galaxies around you between the galaxies. */
    @Volatile var gladeStream = prefs.getBoolean("gladeStream", true)
        set(v) { field = v; prefs.edit().putBoolean("gladeStream", v).apply() }
    @Volatile var wifiOnly = prefs.getBoolean("wifiOnly", true)
        set(v) { field = v; prefs.edit().putBoolean("wifiOnly", v).apply() }
    // Key bumped when the tour changes, so everyone sees the new features once.
    @Volatile var seenTips = prefs.getBoolean("seenTips3", false)
        set(v) { field = v; prefs.edit().putBoolean("seenTips3", v).apply() }
}
