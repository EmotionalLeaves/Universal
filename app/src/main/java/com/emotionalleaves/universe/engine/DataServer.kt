package com.emotionalleaves.universe.engine

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Reads pieces of the big data files in the repo's sky-v1 release with HTTP range requests. */
object DataServer {
    private val resolved = ConcurrentHashMap<String, Pair<String, Long>>()
    /** Tests only: read the files from this folder instead of the network. */
    @Volatile var localDir: java.io.File? = null

    /** Bytes [start, start+len) of a data file (follows GitHub's redirect to its file server, re-resolved every few minutes). */
    fun range(file: String, start: Long, len: Long): ByteArray {
        localDir?.let { d ->
            java.io.RandomAccessFile(java.io.File(d, file), "r").use { f -> val b = ByteArray(len.toInt()); f.seek(start); f.readFully(b); return b }
        }
        var last: Exception? = null
        for (attempt in 0 until 3) {
            try {
                val url = resolvedUrl(file, attempt > 0)
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "UniverseExplorer-app")
                conn.setRequestProperty("Range", "bytes=$start-${start + len - 1}")
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                val code = conn.responseCode
                if (code == 403 || (code == 404 && attempt == 0)) { conn.disconnect(); resolved.remove(file); continue }
                if (code != 206) throw IllegalStateException("data server answered $code")
                val out = ByteArray(len.toInt())
                conn.inputStream.use { input ->
                    var got = 0
                    while (got < out.size) { val k = input.read(out, got, out.size - got); if (k < 0) break; got += k }
                    if (got < out.size) throw java.io.IOException("short read")
                }
                return out
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("download failed")
    }

    /** The file's full size (from a 1-byte range request's Content-Range), or -1. */
    fun totalSize(file: String): Long {
        localDir?.let { return java.io.File(it, file).length() }
        val conn = URL(resolvedUrl(file, true)).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "UniverseExplorer-app")
        conn.setRequestProperty("Range", "bytes=0-0")
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        return try {
            if (conn.responseCode != 206) -1 else conn.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1
        } finally {
            conn.disconnect()
        }
    }

    private fun resolvedUrl(file: String, fresh: Boolean): String {
        val now = System.currentTimeMillis()
        if (!fresh) resolved[file]?.let { (u, at) -> if (now - at < 4 * 60_000) return u }
        var u = PackManager.DATA_BASE + file
        repeat(5) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.requestMethod = "HEAD"
            c.setRequestProperty("User-Agent", "UniverseExplorer-app")
            c.connectTimeout = 15_000
            val code = c.responseCode
            val loc = c.getHeaderField("Location")
            c.disconnect()
            if (code in 300..399 && loc != null) u = loc else {
                if (code == 404) throw java.io.FileNotFoundException(file)
                resolved[file] = u to now
                return u
            }
        }
        resolved[file] = u to now
        return u
    }
}
