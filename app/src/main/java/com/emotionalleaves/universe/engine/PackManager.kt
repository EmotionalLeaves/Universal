package com.emotionalleaves.universe.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/** A downloadable data pack hosted as a GitHub Release file. */
class PackInfo(
    val id: String,
    val title: String,
    val description: String,
    val asset: String,
    val approxMb: Int,
)

/** State of one pack for the UI. */
data class PackState(
    val info: PackInfo,
    val installed: Boolean,
    val sizeBytes: Long,
    val downloading: Boolean,
    val progress: Float,
    val error: String?,
)

/**
 * Downloads, installs and deletes data packs (rule: extra data lives in GitHub Release files under
 * 2 GB each). Packs unzip into files/packs/<id>/ and the world reloads them.
 */
class PackManager(private val context: Context, private val world: World, private val settings: Settings) {
    /**
     * Downloadable data: catalogues from the public data repo (EmotionalLeaves/Universal-data), plus
     * the asteroid list the app builds from the Minor Planet Center. All of them install by themselves
     * on the first run (on Wi-Fi unless "Wi-Fi only" is off).
     */
    val packs = listOf(
        PackInfo(
            GALAXIES, "Galaxies: GLADE+ (4.4 million)",
            "Every GLADE+ galaxy within 800 million light years and a sample of the rest out to 6.5 billion, " +
                "each in its measured place. Replaces generated galaxies wherever real ones are known.",
            "pack-galaxies-glade.zip", 64,
        ),
        PackInfo(
            QUASARS, "Quasars: Milliquas (1 million)",
            "Every quasar and active galaxy in the Million Quasars catalogue (v8) with a redshift, placed at its distance. Tap and search any of them.",
            "pack-quasars-milliquas.zip", 30,
        ),
        PackInfo(
            ASTEROIDS, "Every known asteroid",
            "All ~1.5 million asteroids with known orbits, straight from the Minor Planet Center (updated daily; NASA JPL " +
                "as a backup). Each one moves on its real orbit and can be tapped and searched. About 75 MB on your phone.",
            "mpcorb", 94,
        ),
    )

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var cancelId: String? = null
    private val progress = HashMap<String, Float>()
    private val errors = HashMap<String, String>()
    private var active: String? = null

    /** Called on the main thread whenever states change. */
    var onChange: (() -> Unit)? = null

    fun dir(id: String) = File(context.filesDir, "packs/$id")

    fun installed(id: String) = dir(id).exists() && (dir(id).listFiles()?.isNotEmpty() == true)

    fun states(): List<PackState> = packs.map { p ->
        PackState(p, installed(p.id), sizeOf(dir(p.id)), active == p.id, progress[p.id] ?: 0f, errors[p.id])
    }

    fun totalBytes(): Long = sizeOf(File(context.filesDir, "packs"))

    private fun sizeOf(f: File): Long = if (!f.exists()) 0 else if (f.isFile) f.length() else (f.listFiles()?.sumOf { sizeOf(it) } ?: 0)

    fun onWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private val queue = ArrayDeque<String>()

    /** First run (and after an update adds packs): install everything not installed yet, one at a time. */
    fun autoInstall() {
        if (!settings.autoDownload) return
        if (settings.wifiOnly && !onWifi()) return
        main.post {
            for (p in packs) if (!installed(p.id) && p.id !in queue && active != p.id && errors[p.id] == null) queue.add(p.id)
            next()
        }
    }

    private fun next() {
        if (active != null) return
        val id = queue.removeFirstOrNull() ?: return
        download(id)
    }

    fun download(id: String) {
        val p = packs.firstOrNull { it.id == id } ?: return
        if (active != null) { if (id !in queue) queue.add(id); notifyUi(); return }
        if (settings.wifiOnly && !onWifi()) {
            errors[id] = "Not on Wi-Fi. Turn off \"Wi-Fi only\" to download on mobile data."
            notifyUi()
            return
        }
        errors.remove(id)
        active = id
        progress[id] = 0f
        cancelId = null
        notifyUi()
        exec.execute {
            if (p.id == ASTEROIDS) { buildAsteroids(); return@execute }
            try {
                val url = assetUrl(p.asset) ?: throw IllegalStateException("Pack not published yet")
                val tmp = File(context.cacheDir, "${p.id}.zip")
                fetch(url, tmp, p.id)
                unzip(tmp, dir(p.id))
                tmp.delete()
                main.post { progress[id] = 1f }
                world.packsChanged()
            } catch (e: Exception) {
                dir(p.id).deleteRecursively()
                val msg = when {
                    cancelId == id -> "Cancelled"
                        e is java.io.FileNotFoundException -> "This pack isn't on the data server yet."
                    e is java.net.UnknownHostException -> "No internet connection."
                    else -> e.message ?: e.javaClass.simpleName
                }
                main.post { errors[id] = msg }
            } finally {
                main.post { active = null; notifyUi(); next() }
            }
        }
    }

    /** Downloads the MPC orbit list and turns it into the app's compact format, as it arrives. */
    private fun buildAsteroids() {
        val id = ASTEROIDS
        try {
            AsteroidDb.build(context.filesDir, context.cacheDir, { url ->
                val c = openRetrying(url)
                AsteroidDb.Companion.SizedStream(c.inputStream, c.contentLengthLong)
            },
                { f -> main.post { progress[id] = f; onChange?.invoke() } }, { cancelId == id })
            world.reloadAsteroids()
        } catch (e: Exception) {
            dir(id).deleteRecursively()
            val msg = when {
                cancelId == id -> "Cancelled"
                e is java.net.UnknownHostException -> "No internet connection."
                e is java.io.IOException -> "Download interrupted (${e.message ?: "network error"}). Try again."
                else -> e.message ?: e.javaClass.simpleName
            }
            main.post { errors[id] = msg }
        } finally {
            main.post { active = null; notifyUi(); next() }
        }
    }

    /** [openFollowing] with a few retries (JPL answers 503 when busy). */
    private fun openRetrying(url: String): HttpURLConnection {
        var last: Exception? = null
        for (attempt in 0 until 4) {
            if (cancelId != null) throw IllegalStateException("Cancelled")
            try { return openFollowing(url) } catch (e: Exception) { last = e; Thread.sleep(2000L shl attempt) }
        }
        throw last ?: IllegalStateException("Download failed")
    }

    /** Opens [url] following redirects (HTTP 200 required). */
    private fun openFollowing(url: String): HttpURLConnection {
        var u = url
        repeat(6) {
            val conn = URL(u).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "UniverseExplorer-app")
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            val code = conn.responseCode
            if (code in 300..399) { u = conn.getHeaderField("Location") ?: throw IllegalStateException("Bad redirect"); return@repeat }
            if (code != 200) throw IllegalStateException("Download failed (HTTP $code)")
            return conn
        }
        throw IllegalStateException("Too many redirects")
    }

    fun cancel(id: String) {
        cancelId = id
    }

    fun delete(id: String) {
        if (active == id) return
        exec.execute {
            dir(id).deleteRecursively()
            if (id == ASTEROIDS) world.reloadAsteroids() else world.packsChanged()
            main.post { errors.remove(id); progress.remove(id); notifyUi() }
        }
    }

    private fun notifyUi() {
        main.post { onChange?.invoke() }
    }

    /** Looks up the download URL of a release file through the GitHub API. */
    /** Files live in the public data repo's release (the app repo itself is private). */
    private fun assetUrl(name: String): String? = DATA_BASE + name

    @Suppress("unused")
    private fun assetUrlFromApi(name: String): String? {
        val conn = URL(RELEASE_API).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "UniverseExplorer-app")
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val assets = JSONObject(body).getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name") == name) return a.getString("browser_download_url")
        }
        return null
    }

    private fun fetch(url: String, out: File, id: String) {
        var u = url
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = URL(u).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "UniverseExplorer-app")
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            val code = conn.responseCode
            if (code in 300..399 && redirects < 6) {
                u = conn.getHeaderField("Location") ?: break
                redirects++
                continue
            }
            if (code != 200) throw IllegalStateException("Download failed (HTTP $code)")
            break
        }
        val total = conn.contentLengthLong
        var done = 0L
        conn.inputStream.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(1 shl 16)
                var lastPost = 0L
                while (true) {
                    if (cancelId == id) throw IllegalStateException("Cancelled")
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (total > 0 && done - lastPost > (1 shl 20)) {
                        lastPost = done
                        val f = done.toFloat() / total
                        main.post { progress[id] = f * 0.95f; onChange?.invoke() }
                    }
                }
            }
        }
    }

    private fun unzip(zip: File, target: File) {
        target.deleteRecursively()
        target.mkdirs()
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val f = File(target, e.name)
                if (!f.canonicalPath.startsWith(target.canonicalPath)) continue // zip-slip guard
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zin.copyTo(it) }
                }
            }
        }
    }

    companion object {
        const val ASTEROIDS = "asteroids-mpc"
        const val GALAXIES = "galaxies-glade"
        const val QUASARS = "quasars-milliquas"
        /** Public data repo release with every downloadable file. */
        const val DATA_BASE = "https://github.com/EmotionalLeaves/Universal-data/releases/download/sky-v1/"
        const val RELEASE_API = "https://api.github.com/repos/EmotionalLeaves/Universal/releases/tags/data-v1"
    }
}
