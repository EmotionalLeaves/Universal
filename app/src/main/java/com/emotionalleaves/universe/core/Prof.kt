package com.emotionalleaves.universe.core

/** Tiny profiler for tests: milliseconds per key (sum, worst). Off in normal use. */
object Prof {
    @Volatile var on = false
    val map = java.util.concurrent.ConcurrentHashMap<String, DoubleArray>()

    inline fun <T> time(key: String, block: () -> T): T {
        if (!on) return block()
        val t0 = System.nanoTime()
        try { return block() } finally {
            val ms = (System.nanoTime() - t0) / 1e6
            val a = map.getOrPut(key) { DoubleArray(2) }
            a[0] += ms; if (ms > a[1]) a[1] = ms
        }
    }
}
