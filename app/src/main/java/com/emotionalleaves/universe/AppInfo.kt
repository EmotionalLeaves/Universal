package com.emotionalleaves.universe

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object AppInfo {
    /** For example "0.0.7 (build 7)". */
    fun version(context: Context): String = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName} (build $code)"
    } catch (_: Throwable) {
        "unknown"
    }
}
