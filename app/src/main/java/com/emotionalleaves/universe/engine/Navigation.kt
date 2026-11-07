package com.emotionalleaves.universe.engine

import android.content.Context
import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.core.UPos
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** One search hit. */
class SearchHit(val id: String, val name: String, val detail: String, val score: Int)

/**
 * Searches everything with a name: planets, moons, named stars and designations, HIP/HD numbers,
 * exoplanets and their stars, nebulae, clusters, galaxies (names, NGC, PGC) and generated objects
 * you have visited or bookmarked.
 */
class SearchIndex(private val world: World) {
    private class Entry(val key: String, val name: String, val detail: String, val id: String, val weight: Int)

    @Volatile private var entries: List<Entry>? = null

    fun ready() = entries != null

    /** Builds the index (call on a background thread). */
    fun build() {
        val out = ArrayList<Entry>(80_000)
        for (b in world.solar) out.add(Entry(b.name.lowercase(Locale.US), b.name, b.subtitle, b.id, 100))
        out.add(Entry("milky way", "Milky Way", "Our galaxy", GalaxyBody.MILKY_WAY_ID, 90))
        out.add(Entry("observable universe", "Observable universe", "Everything we can see", "universe", 80))
        world.realStars?.let { rs ->
            for ((name, id) in rs.nameToId) {
                val info = rs.info(id)
                val display = info?.displayName() ?: name
                out.add(Entry(name, display, "Star" + (info?.spect?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""), "hyg:$id",
                    if (info?.proper?.isNotEmpty() == true) 60 else 30))
            }
        }
        world.exo?.let { cat ->
            for ((host, rows) in cat.byHost) {
                val sid = world.starIdForHost(host)
                if (sid != null) out.add(Entry(host.lowercase(Locale.US), host, "Star with ${rows.size} known planet${if (rows.size > 1) "s" else ""}", sid, 50))
                for (r in rows) out.add(Entry(r.name.lowercase(Locale.US), r.name, "Exoplanet · ${r.host}", "exo:${r.name}", 55))
            }
        }
        for (d in world.dsos) {
            val detail = DsoBody.typeLabel(d.type)
            out.add(Entry(d.name.lowercase(Locale.US), d.name, detail, d.id, if (d.messier.isNotEmpty()) 58 else 35))
            if (d.messier.isNotEmpty()) out.add(Entry("m${d.messier}", "M${d.messier}", "${d.name} · $detail", d.id, 58))
            if (d.catalogName != d.name) out.add(Entry(d.catalogName.lowercase(Locale.US), d.catalogName, "${d.name} · $detail", d.id, 30))
        }
        for (g in world.galaxies) {
            if (g.isMilkyWay) continue
            if (!g.name.startsWith("PGC ")) out.add(Entry(g.name.lowercase(Locale.US), g.name, g.subtitle, g.id, if (g.name.startsWith("NGC") || g.name.startsWith("IC")) 30 else 52))
        }
        world.smallBodies?.let { sb ->
            for (i in 0 until sb.count) {
                val n = sb.names[i]
                // Numbered asteroids: also findable by name alone ("Vesta" as well as "4 Vesta").
                val bare = n.substringAfter(' ', n)
                val id = (if (sb.isComet(i)) "sbc:" else "sba:") + n
                val pri = if (sb.isComet(i)) 40 else 38
                out.add(Entry(n.lowercase(Locale.US), n, SmallBodies.className(sb.cls[i]), id, pri))
                if (bare != n && bare.firstOrNull()?.isLetter() == true) out.add(Entry(bare.lowercase(Locale.US), n, SmallBodies.className(sb.cls[i]), id, pri))
            }
        }
        val ex = world.exotic
        for (b in ex.pulsars) out.add(Entry(b.name.lowercase(Locale.US), b.name, "Pulsar", b.id, 36))
        for (b in ex.clusters) out.add(Entry(b.name.lowercase(Locale.US), b.name, "Galaxy cluster", b.id, 40))
        for (b in ex.quasars) if (b.name.isNotBlank()) out.add(Entry(b.name.lowercase(Locale.US), b.name, "Quasar", b.id, 30))
        for ((name, id) in world.visitedGenerated()) out.add(Entry(name.lowercase(Locale.US), name, "Generated · visited", id, 45))
        entries = out
    }

    fun search(query: String, limit: Int = 40): List<SearchHit> {
        val q = query.trim().lowercase(Locale.US)
        if (q.isEmpty()) return emptyList()
        val hits = ArrayList<SearchHit>()
        // Catalogue numbers: HIP / HD / PGC / ids.
        val num = q.filter { it.isDigit() }
        if (num.isNotEmpty()) {
            val rs = world.realStars
            if (q.startsWith("hip")) rs?.hipToId?.get(num)?.let { id -> rs.info(id)?.let { hits.add(SearchHit("hyg:$id", it.displayName(), "HIP $num", 200)) } }
            if (q.startsWith("hd")) rs?.hdToId?.get(num)?.let { id -> rs.info(id)?.let { hits.add(SearchHit("hyg:$id", it.displayName(), "HD $num", 200)) } }
            if (q.startsWith("pgc")) world.galaxyById("gal:pgc$num")?.let { hits.add(SearchHit(it.id, it.name, "PGC $num", 200)) }
        }
        val idPrefixes = listOf("gs:", "gal:", "hyg:", "exo:", "sba:", "sbc:", "psr:", "qso:", "gcl:", "rem:", "sol:")
        if (idPrefixes.any { q.startsWith(it) }) {
            world.find(query.trim())?.let { hits.add(SearchHit(it.id, it.name, it.subtitle, 300)) }
        }
        val list = entries ?: return hits
        val seen = HashSet<String>()
        hits.forEach { seen.add(it.id) }
        // Keep only the best few hundred matches (a 1-letter query matches most of 300,000 names).
        val keep = limit * 6
        val heap = java.util.PriorityQueue<Pair<Int, Entry>>(keep + 1, compareBy { it.first })
        val spaced = " $q"
        for (e in list) {
            val k = e.key
            val base = when {
                k == q -> 1000
                k.startsWith(q) -> 600
                k.contains(spaced) -> 400
                q.length >= 2 && k.contains(q) -> 200
                else -> continue
            }
            val s = base - (k.length - q.length) + e.weight
            if (heap.size < keep) heap.add(s to e)
            else if (s > heap.peek()!!.first) { heap.poll(); heap.add(s to e) }
        }
        val scored = heap.map { SearchHit(it.second.id, it.second.name, it.second.detail, it.first) }.sortedByDescending { it.score }
        for (h in scored) {
            if (hits.size >= limit) break
            if (seen.add(h.id)) hits.add(h)
        }
        return hits
    }
}

/** A saved view: what you were looking at and exactly where the camera was. */
class Bookmark(
    val name: String,
    val bodyId: String?,
    val focus: UPos,
    val distance: Double,
    val orientation: Quatd,
    val created: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("body", bodyId ?: "").put("focus", focus.encode()).put("distance", distance)
        .put("q", "${orientation.w},${orientation.x},${orientation.y},${orientation.z}").put("created", created)

    companion object {
        fun fromJson(o: JSONObject): Bookmark? = try {
            val q = o.getString("q").split(",").map { it.toDouble() }
            Bookmark(
                o.getString("name"), o.optString("body", "").ifEmpty { null }, UPos.decode(o.getString("focus"))!!,
                o.getDouble("distance"), Quatd(q[0], q[1], q[2], q[3]), o.optDouble("created", 0.0).toLong(),
            )
        } catch (_: Exception) {
            null
        }
    }
}

class Bookmarks(context: Context) {
    private val prefs = context.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)

    fun list(): List<Bookmark> = try {
        val arr = JSONArray(prefs.getString("list", "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { Bookmark.fromJson(arr.getJSONObject(it)) }
    } catch (_: Exception) {
        emptyList()
    }

    fun save(list: List<Bookmark>) {
        val arr = JSONArray()
        for (b in list) arr.put(b.toJson())
        prefs.edit().putString("list", arr.toString()).apply()
    }

    fun add(b: Bookmark) = save(listOf(b) + list().filter { it.name != b.name || it.bodyId != b.bodyId })

    fun remove(b: Bookmark) = save(list().filter { !(it.name == b.name && it.created == b.created) })
}
