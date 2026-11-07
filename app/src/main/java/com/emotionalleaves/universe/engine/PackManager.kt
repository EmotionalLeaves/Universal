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
    val packs = listOf(
        PackInfo(
            "stars-athyg", "750,000 more real stars",
            "AT-HYG (Tycho-2 + Gaia DR3) down to magnitude 11. Fills the sky with real stars where generated ones were.",
            "pack-stars-athyg.zip", 15,
        ),
        PackInfo(
            "galaxies-glade", "Millions of real galaxies",
            "GLADE+ galaxy catalogue out to about 6.5 billion light years. Replaces generated cosmic web inside that range.",
            "pack-galaxies-glade.zip", 60,
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

    fun download(id: String) {
        val p = packs.firstOrNull { it.id == id } ?: return
        if (active != null) return
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
                val msg = if (cancelId == id) "Cancelled" else (e.message ?: e.javaClass.simpleName)
                main.post { errors[id] = msg }
            } finally {
                main.post { active = null; notifyUi() }
            }
        }
    }

    fun cancel(id: String) {
        cancelId = id
    }

    fun delete(id: String) {
        if (active == id) return
        exec.execute {
            dir(id).deleteRecursively()
            world.packsChanged()
            main.post { errors.remove(id); progress.remove(id); notifyUi() }
        }
    }

    private fun notifyUi() {
        main.post { onChange?.invoke() }
    }

    /** Looks up the download URL of a release file through the GitHub API. */
    private fun assetUrl(name: String): String? {
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
        const val RELEASE_API = "https://api.github.com/repos/EmotionalLeaves/Universal/releases/tags/data-v1"
    }
}
