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
    private class Entry(val key: String, val name: String, val detail: String, val id: String, val weight: Int) {
        /** Letters and digits only: "TON 618", "ton618" and "Ton-618" all match. */
        val norm = normalize(key)
    }

    @Volatile private var entries: List<Entry>? = null

    fun ready() = entries != null

    /** Builds the index (call on a background thread). */
    fun build() {
        val out = ArrayList<Entry>(80_000)
        // Record holders and famous objects first: they are what people type in.
        for (b in Famous.all) {
            out.add(Entry(b.name.lowercase(Locale.US), b.name, b.subtitle, b.id, 120))
            for (a in b.aliases) out.add(Entry(a.lowercase(Locale.US), b.name, b.subtitle, b.id, 110))
        }
        for ((alias, messier) in DSO_ALIASES) {
            val d = world.dsos.firstOrNull { it.messier.trimStart('0') == messier }
            if (d != null) { out.add(Entry(alias, d.name, "${DsoBody.typeLabel(d.type)} · M$messier", d.id, 100)); continue }
            // Messier galaxies live in the galaxy catalogue.
            val ngc = MESSIER_GALAXIES.firstOrNull { it.first.toString() == messier }?.second ?: continue
            val g = GalaxyCatalog.designations[ngc]?.let { world.galaxyById(it) } ?: continue
            out.add(Entry(alias, g.name, "M$messier · ${g.subtitle}", g.id, 100))
        }
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
        // NGC / IC numbers and Messier numbers of galaxies (M31 = NGC 224).
        for ((des, id) in GalaxyCatalog.designations) {
            val g = world.galaxyById(id) ?: continue
            out.add(Entry(des.lowercase(Locale.US), g.name, "$des · ${g.subtitle}", id, 45))
        }
        for ((m, ngc) in MESSIER_GALAXIES) {
            val id = GalaxyCatalog.designations[ngc] ?: continue
            val g = world.galaxyById(id) ?: continue
            out.add(Entry("m$m", g.name, "M$m · ${g.subtitle}", id, 70))
            out.add(Entry("messier $m", g.name, "M$m · ${g.subtitle}", id, 60))
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
        fun offer(s: Int, e: Entry) {
            if (heap.size < keep) heap.add(s to e)
            else if (s > heap.peek()!!.first) { heap.poll(); heap.add(s to e) }
        }
        val spaced = " $q"
        val qn = normalize(q)
        for (e in list) {
            val k = e.key
            val n = e.norm
            val base = when {
                k == q || (qn.isNotEmpty() && n == qn) -> 1000
                k.startsWith(q) || (qn.length >= 2 && n.startsWith(qn)) -> 600
                k.contains(spaced) -> 400
                q.length >= 2 && k.contains(q) -> 200
                qn.length >= 3 && n.contains(qn) -> 180
                else -> continue
            }
            offer(base - (k.length - q.length) + e.weight, e)
        }
        // Few matches: allow a typo or two ("betelguese", "andromida") among named things.
        if (heap.size < 8 && qn.length >= 4) {
            val maxEd = if (qn.length >= 8) 2 else 1
            // Designations differ by one character on purpose ("Gaia BH1" vs "BH3", "2024 OR4" vs "YR4"):
            // only allow typos in the letters, never in the numbers.
            val qDigits = qn.filter { it.isDigit() }
            for (e in list) {
                if (e.weight < 35) continue
                val n = e.norm
                if (n.filter { it.isDigit() } != qDigits) continue
                if (kotlin.math.abs(n.length - qn.length) > maxEd && n.length < qn.length) continue
                val cand = if (n.length > qn.length + maxEd) n.substring(0, qn.length) else n
                val d = editDistance(qn, cand, maxEd)
                if (d <= maxEd) offer(150 - d * 30 + e.weight - kotlin.math.abs(n.length - qn.length), e)
            }
        }
        val scored = heap.map { SearchHit(it.second.id, it.second.name, it.second.detail, it.first) }.sortedByDescending { it.score }
        for (h in scored) {
            if (hits.size >= limit) break
            if (seen.add(h.id)) hits.add(h)
        }
        return hits
    }
}

/** Lower-case letters and digits only (spaces, dashes, dots and Greek-letter accents dropped). */
fun normalize(s: String): String {
    val b = StringBuilder(s.length)
    for (c in s) {
        val l = c.lowercaseChar()
        when {
            l in 'a'..'z' || l in '0'..'9' -> b.append(l)
            l == 'ö' || l == 'ó' || l == 'ò' -> b.append('o')
            l == 'é' || l == 'è' || l == 'ë' -> b.append('e')
            l == 'á' || l == 'à' || l == 'ä' -> b.append('a')
            l == 'ü' || l == 'ú' -> b.append('u')
            l == 'í' || l == 'ï' -> b.append('i')
        }
    }
    return b.toString()
}

/** Edit distance with transpositions, giving up early past [max]. */
fun editDistance(a: String, b: String, max: Int): Int {
    val n = a.length; val m = b.length
    if (kotlin.math.abs(n - m) > max) return max + 1
    var prev2 = IntArray(m + 1)
    var prev = IntArray(m + 1) { it }
    var cur = IntArray(m + 1)
    for (i in 1..n) {
        cur[0] = i
        var best = cur[0]
        for (j in 1..m) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
            cur[j] = v
            if (v < best) best = v
        }
        if (best > max) return max + 1
        val t = prev2; prev2 = prev; prev = cur; cur = t
    }
    return prev[m]
}

/** Messier galaxies and their NGC numbers. */
val MESSIER_GALAXIES = listOf(
    31 to "NGC 224", 32 to "NGC 221", 33 to "NGC 598", 49 to "NGC 4472", 51 to "NGC 5194", 58 to "NGC 4579", 59 to "NGC 4621",
    60 to "NGC 4649", 61 to "NGC 4303", 63 to "NGC 5055", 64 to "NGC 4826", 65 to "NGC 3623", 66 to "NGC 3627", 74 to "NGC 628",
    77 to "NGC 1068", 81 to "NGC 3031", 82 to "NGC 3034", 83 to "NGC 5236", 84 to "NGC 4374", 85 to "NGC 4382", 86 to "NGC 4406",
    87 to "NGC 4486", 88 to "NGC 4501", 89 to "NGC 4552", 90 to "NGC 4569", 91 to "NGC 4548", 94 to "NGC 4736", 95 to "NGC 3351",
    96 to "NGC 3368", 98 to "NGC 4192", 99 to "NGC 4254", 100 to "NGC 4321", 101 to "NGC 5457", 104 to "NGC 4594", 105 to "NGC 3379",
    106 to "NGC 4258", 108 to "NGC 3556", 109 to "NGC 3992", 110 to "NGC 205",
)

/** Nicknames of famous nebulae and clusters (Messier number). */
val DSO_ALIASES = listOf(
    "pillars of creation" to "16", "eagle nebula" to "16", "seven sisters" to "45", "pleiades" to "45",
    "orion nebula" to "42", "crab nebula" to "1", "ring nebula" to "57", "dumbbell nebula" to "27",
    "whirlpool galaxy" to "51", "sombrero galaxy" to "104", "andromeda" to "31", "beehive" to "44",
    "lagoon nebula" to "8", "omega nebula" to "17", "trifid nebula" to "20", "wild duck cluster" to "11",
    "hercules cluster" to "13", "owl nebula" to "97", "pinwheel galaxy" to "101", "bode's galaxy" to "81", "cigar galaxy" to "82",
)

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
