package com.emotionalleaves.universe.core

/**
 * Fast reader for one tab-separated line: field boundaries are found once and numbers are parsed
 * straight from the characters. String.split + toDouble cost about 12 µs per number on a phone that
 * has not compiled the app yet (most of the catalogue load time); this is several times faster.
 */
class TsvRow(capacity: Int = 32) {
    var line: String = ""
        private set
    private var starts = IntArray(capacity)
    private var ends = IntArray(capacity)
    var size = 0
        private set

    fun set(l: String): TsvRow {
        line = l
        size = 0
        var s = 0
        val n = l.length
        var i = 0
        while (i <= n) {
            if (i == n || l[i] == '\t') {
                if (size == starts.size) { starts = starts.copyOf(size * 2); ends = ends.copyOf(size * 2) }
                starts[size] = s; ends[size] = i; size++
                s = i + 1
            }
            i++
        }
        return this
    }

    fun isEmpty(i: Int) = i >= size || starts[i] == ends[i]

    fun str(i: Int): String = if (i >= size) "" else line.substring(starts[i], ends[i])

    fun int(i: Int): Int? {
        if (isEmpty(i)) return null
        var p = starts[i]; val e = ends[i]
        var neg = false
        if (line[p] == '-') { neg = true; p++ } else if (line[p] == '+') p++
        if (p >= e) return null
        var v = 0L
        while (p < e) {
            val c = line[p]
            if (c < '0' || c > '9') return str(i).trim().toIntOrNull()
            v = v * 10 + (c - '0'); p++
            if (v > Int.MAX_VALUE) return null
        }
        return (if (neg) -v else v).toInt()
    }

    fun num(i: Int): Double? {
        if (isEmpty(i)) return null
        var p = starts[i]; val e = ends[i]
        var neg = false
        if (line[p] == '-') { neg = true; p++ } else if (line[p] == '+') p++
        var mant = 0L
        var digits = 0
        var scale = 0
        var seenDot = false
        var any = false
        while (p < e) {
            val c = line[p]
            if (c in '0'..'9') {
                any = true
                if (digits < 17) { mant = mant * 10 + (c - '0'); if (mant != 0L) digits++; if (seenDot) scale++ }
                else if (!seenDot) scale--
                p++
            } else if (c == '.' && !seenDot) {
                seenDot = true; p++
            } else break
        }
        if (!any) return str(i).trim().toDoubleOrNull()
        var exp = -scale
        if (p < e) {
            val c = line[p]
            if (c != 'e' && c != 'E') return str(i).trim().toDoubleOrNull()
            p++
            var eneg = false
            if (p < e && (line[p] == '-' || line[p] == '+')) { eneg = line[p] == '-'; p++ }
            var ev = 0
            var eany = false
            while (p < e && line[p] in '0'..'9') { ev = ev * 10 + (line[p] - '0'); p++; eany = true }
            if (!eany || p < e) return str(i).trim().toDoubleOrNull()
            exp += if (eneg) -ev else ev
        }
        if (exp < -300 || exp > 300) return str(i).trim().toDoubleOrNull()
        val m = mant.toDouble()
        val v = when {
            exp == 0 -> m
            exp in 1..22 -> m * POW10[exp]
            exp in -22..-1 -> m / POW10[-exp]
            else -> m * Math.pow(10.0, exp.toDouble())
        }
        return if (neg) -v else v
    }

    private companion object {
        val POW10 = DoubleArray(23) { Math.pow(10.0, it.toDouble()) }
    }
}
