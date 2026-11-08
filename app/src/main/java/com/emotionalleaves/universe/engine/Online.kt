package com.emotionalleaves.universe.engine

import android.content.Context
import com.emotionalleaves.universe.core.Astro
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Looks up anything astronomers have named, when it isn't in the app's own catalogues:
 * - SIMBAD (Harvard mirror first, then Strasbourg): ~20 million stars, galaxies, nebulae, quasars…
 * - NED (NASA/IPAC) as the backup for galaxies and quasars.
 * - JPL Small-Body Database: every asteroid and comet, with its real orbit.
 * Results are kept on the phone, so bookmarks and recent searches work offline later.
 */
object Online {
    private var prefs: android.content.SharedPreferences? = null
    private val cache = ConcurrentHashMap<String, Body>()
    /** Everything looked up this session (drawn and labelled by the renderer). */
    @Volatile var resolved: List<Body> = emptyList()
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("online", Context.MODE_PRIVATE)
    }

    private const val SBDB = "https://ssd-api.jpl.nasa.gov/sbdb.api?phys-par=1&sstr="

    /** The HTTP GET used for every lookup (tests swap it for canned replies). */
    @Volatile var fetcher: (String, Int) -> Pair<Int, String> = { url, t -> httpGet(url, t) }

    fun get(url: String, timeoutMs: Int = 12_000): Pair<Int, String> = fetcher(url, timeoutMs)

    private fun httpGet(url: String, timeoutMs: Int): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "UniverseExplorer-app")
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        return try {
            val code = conn.responseCode
            val s = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            code to s
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /**
     * Searches online (blocking; call off the main thread). Returns hits whose ids [find] can
     * rebuild. Never throws: no network just means no hits.
     */
    fun search(query: String): List<SearchHit> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val out = ArrayList<SearchHit>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val sim = pool.submit<Body?> { try { sesame(q) } catch (_: Exception) { null } }
            val sb = pool.submit<List<Body>> { try { sbdb(q) } catch (_: Exception) { emptyList() } }
            val a = try { sim.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { null }
            val b = try { sb.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { emptyList() }
            for (body in listOfNotNull(a) + b) {
                remember(body)
                out.add(SearchHit(body.id, body.name, body.subtitle + " · online", 90))
            }
        } finally {
            pool.shutdown()
        }
        return out
    }

    /** Rebuilds an online object from its id: memory, then the phone's cache, then the network. */
    fun find(id: String): Body? {
        cache[id]?.let { return it }
        val saved = prefs?.getString(id, null)
        if (saved != null) {
            try { fromJson(JSONObject(saved))?.let { cache[id] = it; publish(); return it } } catch (_: Exception) {}
        }
        val key = id.removePrefix("net:")
        val b = try {
            when {
                key.startsWith("sim:") -> sesame(key.removePrefix("sim:"))
                key.startsWith("sb:") -> sbdb(key.removePrefix("sb:")).firstOrNull()
                else -> null
            }
        } catch (_: Exception) { null }
        b?.let { remember(it) }
        return b
    }

    private fun remember(b: Body) {
        cache[b.id] = b
        val j = when (b) { is NetBody -> b.json; is NetSmallBody -> b.json; else -> null }
        if (j != null) prefs?.edit()?.putString(b.id, j.toString())?.apply()
        publish()
    }

    private fun publish() { resolved = cache.values.toList() }

    private fun fromJson(j: JSONObject): Body? = when (j.optString("t")) {
        "sim" -> NetBody.fromJson(j)
        "sb" -> NetSmallBody.fromJson(j)
        else -> null
    }

    // ------------------------------------------------------------------ SIMBAD (and NED for galaxies)

    private val SIMBAD = listOf(
        "https://simbad.cfa.harvard.edu/simbad/sim-id?output.format=ASCII&Ident=",
        "https://simbad.cds.unistra.fr/simbad/sim-id?output.format=ASCII&Ident=",
    )
    private const val NED = "https://ned.ipac.caltech.edu/srs/ObjectLookup?name="

    private fun sexagesimal(ra: String, dec: String): Pair<Double, Double>? {
        val r = ra.trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
        val dd = dec.trim()
        val sign = if (dd.startsWith("-")) -1.0 else 1.0
        val d = dd.trimStart('+', '-').split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
        if (r.isEmpty() || d.isEmpty()) return null
        val raDeg = 15.0 * (r[0] + r.getOrElse(1) { 0.0 } / 60 + r.getOrElse(2) { 0.0 } / 3600)
        val decDeg = sign * (d[0] + d.getOrElse(1) { 0.0 } / 60 + d.getOrElse(2) { 0.0 } / 3600)
        return raDeg to decDeg
    }

    /** First number after "Label:" in SIMBAD's ASCII page ("~" = none). */
    private fun field(text: String, label: String): Double? {
        val line = text.lineSequence().firstOrNull { it.trimStart().startsWith(label) } ?: return null
        return line.substringAfter(':').trim().split(Regex("\\s+")).firstOrNull()?.toDoubleOrNull()
    }

    /** Resolves a name with SIMBAD (any star, galaxy, nebula…); NED is the backup for galaxies. */
    fun sesame(name: String): NetBody? = simbad(name) ?: ned(name)

    fun simbad(name: String): NetBody? {
        var text: String? = null
        // Nicknames are stored as "NAME …" ("Gaia BH3" alone reads as a malformed Gaia catalogue number).
        val tries = if (name.startsWith("NAME ", true)) listOf(name) else listOf(name, "NAME $name")
        outer@ for (ident in tries) {
            for (base in SIMBAD) {
                try {
                    val (code, s) = get(base + enc(ident), 10_000)
                    if (code == 200 && s.contains("Coordinates(ICRS")) { text = s; break@outer }
                    if (code == 200 && (s.contains("not found") || s.contains("!!"))) continue@outer
                } catch (_: Exception) {
                }
            }
        }
        val t = text ?: return null
        val objLine = t.lineSequence().firstOrNull { it.startsWith("Object ") } ?: return null
        val parts = objLine.removePrefix("Object ").split("---").map { it.trim() }
        val oname = parts.getOrNull(0)?.ifEmpty { null } ?: name
        val otype = parts.getOrNull(1) ?: ""
        val coordLine = t.lineSequence().firstOrNull { it.startsWith("Coordinates(ICRS") } ?: return null
        val c = coordLine.substringAfter("):").trim()
        // "12 28 24.96  +31 28 37.62 (Opt ) A …": RA is the first three numbers, Dec the next three.
        val tokens = c.split(Regex("\\s+"))
        val signAt = tokens.indexOfFirst { it.startsWith("+") || it.startsWith("-") }
        if (signAt <= 0) return null
        val decTokens = tokens.drop(signAt).takeWhile { it.toDoubleOrNull() != null || it.startsWith("+") || it.startsWith("-") }.take(3)
        val (ra, dec) = sexagesimal(tokens.take(signAt).joinToString(" "), decTokens.joinToString(" ")) ?: return null
        // Show the name you typed when SIMBAD's main id is a catalogue code: "Betelgeuse (alf Ori)".
        val main = pretty(oname)
        val display = if (normalize(main) == normalize(name) || name.any { it.isDigit() } && !main.any { it.isLetter() && it.isLowerCase() }) main
            else name.trim().split(Regex("\\s+")).joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } } + " ($main)"
        val j = JSONObject()
            .put("t", "sim").put("query", name).put("name", display).put("otype", otype).put("ra", ra).put("dec", dec)
        field(t, "Parallax:")?.let { j.put("plx", it) }
        // No usable parallax or redshift: ask SIMBAD's table of published distances.
        val plx = field(t, "Parallax:")
        val zz = field(t, "Redshift:")
        val oid = Regex("OID=@(\\d+)").find(objLine)?.groupValues?.get(1)
        if ((plx == null || plx <= 0.05) && (zz == null || zz < 0.01) && oid != null) measuredDistancePc(oid)?.let { j.put("distPc", it) }
        field(t, "Redshift:")?.let { j.put("z", it) }
        field(t, "Flux V :")?.let { j.put("vmag", it) }
        t.lineSequence().firstOrNull { it.startsWith("Spectral type:") }?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.firstOrNull()
            ?.takeIf { it != "~" }?.let { j.put("sp", it) }
        t.lineSequence().firstOrNull { it.startsWith("Morphological type:") }?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.firstOrNull()
            ?.takeIf { it != "~" && !it.startsWith("?") }?.let { j.put("mtype", it) }
        field(t, "Angular size:")?.let { j.put("size", it) }
        return NetBody.fromJson(j)
    }

    /** Median of SIMBAD's published distances for object [oid] (parsecs), or null. */
    private fun measuredDistancePc(oid: String): Double? {
        val q = "SELECT dist, unit FROM mesDistance WHERE oidref = $oid"
        for (host in listOf("https://simbad.cfa.harvard.edu", "https://simbad.cds.unistra.fr")) {
            try {
                val (code, s) = get("$host/simbad/sim-tap/sync?REQUEST=doQuery&LANG=ADQL&FORMAT=tsv&QUERY=" + enc(q), 10_000)
                if (code != 200) continue
                val d = s.lineSequence().drop(1).mapNotNull { l ->
                    val p = l.split('\t').map { it.trim().trim('"') }
                    val v = p.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
                    when (p.getOrNull(1)?.lowercase(Locale.US)) { "pc" -> v; "kpc" -> v * 1e3; "mpc" -> v * 1e6; else -> null }
                }.filter { it > 0 }.sorted().toList()
                if (d.isNotEmpty()) return d[d.size / 2]
                return null
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** NASA/IPAC Extragalactic Database lookup (galaxies, quasars, clusters). */
    fun ned(name: String): NetBody? {
        val (code, s) = try { get(NED + enc(name), 10_000) } catch (_: Exception) { return null }
        if (code != 200 || !s.contains("Preferred")) return null
        val o = JSONObject(s)
        val pref = o.optJSONObject("Preferred") ?: return null
        val pos = pref.optJSONObject("Position") ?: return null
        val ra = pos.optDouble("RA", Double.NaN); val dec = pos.optDouble("Dec", Double.NaN)
        if (ra.isNaN() || dec.isNaN()) return null
        val j = JSONObject().put("t", "sim").put("query", name)
            .put("name", pref.optString("Name", name).replace(Regex("\\s+"), " ")).put("ra", ra).put("dec", dec)
            .put("otype", pref.optJSONObject("ObjType")?.optString("Value", "G") ?: "G").put("src", "NED")
        pref.optJSONObject("Redshift")?.optDouble("Value", Double.NaN)?.takeIf { !it.isNaN() }?.let { j.put("z", it) }
        return NetBody.fromJson(j)
    }

    /** SIMBAD main ids: "NAME Betelgeuse" -> "Betelgeuse", "* alf Ori" -> "alf Ori", "M  31" -> "M 31". */
    fun pretty(id: String): String {
        var s = id.trim()
        if (s.startsWith("NAME ")) s = s.removePrefix("NAME ")
        if (s.startsWith("* ")) s = s.removePrefix("* ")
        if (s.startsWith("V* ")) s = s.removePrefix("V* ")
        return s.replace(Regex("\\s+"), " ")
    }

    // ------------------------------------------------------------------ JPL Small-Body Database

    fun sbdb(name: String): List<Body> {
        val (code, s) = get(SBDB + enc(name))
        if (s.isBlank()) return emptyList()
        val o = JSONObject(s)
        if (o.has("list")) {
            // Several matches: take up to three (each needs its own lookup).
            val list = o.getJSONArray("list")
            val out = ArrayList<Body>()
            for (k in 0 until minOf(3, list.length())) {
                val pdes = list.getJSONObject(k).optString("pdes")
                if (pdes.isEmpty()) continue
                try {
                    val (c2, s2) = get(SBDB + enc(pdes))
                    if (c2 == 200) NetSmallBody.fromSbdb(JSONObject(s2))?.let { out.add(it) }
                } catch (_: Exception) {
                }
            }
            return out
        }
        if (code != 200 || !o.has("orbit")) return emptyList()
        return listOfNotNull(NetSmallBody.fromSbdb(o))
    }
}

/** A star, galaxy, nebula or anything else found in SIMBAD. Fixed position. */
class NetBody(
    val json: JSONObject,
    override val id: String,
    override val name: String,
    override val kind: Kind,
    private val pos: UPos,
    private val sub: String,
    private val extra: List<Pair<String, String>>,
    override val radius: Double,
    override val color: Int,
    val distanceKnown: Boolean,
) : Body() {
    override val source = Source.REAL
    override fun position(jd: Double) = pos
    override val subtitle get() = sub
    override fun facts(jd: Double) = extra
    override fun viewDistance() = when {
        radius > 0 -> radius * 4
        kind == Kind.STAR -> 2e11
        else -> 1e15
    }

    companion object {
        fun fromJson(j: JSONObject): NetBody? {
            val ra = j.optDouble("ra", Double.NaN); val dec = j.optDouble("dec", Double.NaN)
            if (ra.isNaN() || dec.isNaN()) return null
            val name = j.optString("name")
            val otype = j.optString("otype")
            val (kind, label) = kindOf(otype)
            val plx = j.optDouble("plx", Double.NaN)
            val z = j.optDouble("z", Double.NaN).takeIf { !it.isNaN() } ?: j.optDouble("vel", Double.NaN).takeIf { !it.isNaN() }?.div(299_792.458)
            var known = true
            val distPc = j.optDouble("distPc", Double.NaN)
            val distM = when {
                !distPc.isNaN() && distPc > 0 -> distPc * Units.PC
                !plx.isNaN() && plx > 0.05 && kind != Kind.GALAXY && kind != Kind.QUASAR && kind != Kind.GALAXY_CLUSTER -> 1000.0 / plx * Units.PC
                z != null && z > 0.0005 -> Famous.comovingMpc(z) * 1e6 * Units.PC
                else -> { known = false; typical(kind) }
            }
            val pos = UPos.ZERO + Vec3d.fromRaDec(ra, dec) * distM
            val facts = ArrayList<Pair<String, String>>()
            facts.add("Type" to label + (j.optString("sp").takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "") +
                (j.optString("mtype").takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""))
            facts.add("Distance" to if (known) Units.formatDistance(distM) + (if (!distPc.isNaN() && distPc > 0) " (published measurements)" else if (!plx.isNaN() && plx > 0.05 && kind == Kind.STAR) String.format(Locale.US, " (parallax %.3f mas)", plx) else if (z != null && z > 0.0005) String.format(Locale.US, " (redshift %.4f)", z) else "")
                else "Not measured — shown at a typical distance in its direction")
            facts.add("Sky position" to String.format(Locale.US, "RA %.4f°, Dec %+.4f°", ra, dec))
            if (j.has("vmag")) facts.add("Brightness" to String.format(Locale.US, "magnitude %.2f (V)", j.optDouble("vmag")))
            facts.add("Data" to if (j.optString("src") == "NED") "NASA/IPAC Extragalactic Database, looked up online" else "SIMBAD astronomical database (CDS, Strasbourg), looked up online")
            val color = when (kind) {
                Kind.STAR -> 0xFFF0D8
                Kind.GALAXY -> 0xD8E0FF
                Kind.QUASAR -> 0xC8D8FF
                Kind.NEBULA -> 0xFFB0C8
                Kind.BLACK_HOLE -> 0xFFC080
                else -> 0xE0E8FF
            }
            val sizeArcmin = j.optDouble("size", Double.NaN)
            val radius = when {
                kind == Kind.STAR -> Units.R_SUN
                !sizeArcmin.isNaN() && sizeArcmin > 0 && known -> distM * Math.toRadians(sizeArcmin / 60.0) / 2
                else -> 0.0
            }
            if (!sizeArcmin.isNaN() && sizeArcmin > 0) facts.add(3.coerceAtMost(facts.size), "Size on the sky" to String.format(Locale.US, "%.2f arcminutes", sizeArcmin))
            return NetBody(j, "net:sim:" + j.optString("query", name), name, kind, pos, label, facts, radius, color, known)
        }

        private fun typical(k: Kind) = when (k) {
            Kind.GALAXY, Kind.GALAXY_CLUSTER, Kind.QUASAR -> 100e6 * Units.PC
            else -> 1000 * Units.PC
        }

        /** SIMBAD object type codes (short or long form) to the app's kinds. */
        fun kindOf(t: String): Pair<Kind, String> {
            val s = t.trim()
            val l = s.lowercase(Locale.US)
            return when {
                l in setOf("qso", "q?", "bla", "bll", "blazar", "bl?") || l.contains("quasar") -> Kind.QUASAR to "Quasar"
                l in setOf("opc", "cl*", "glc", "as*", "c?*", "gl?", "open_cl", "globcluster", "gc") -> Kind.CLUSTER to "Star cluster"
                l in setOf("clg", "gro", "grg", "cgg", "scg", "pcg", "c?g", "supercl") || l.contains("clg") -> Kind.GALAXY_CLUSTER to "Galaxy cluster"
                l in setOf("agn", "sy1", "sy2", "seyfert", "liner", "lin", "agn?") -> Kind.GALAXY to "Active galaxy"
                l in setOf("g", "gic", "gig", "gip", "bic", "sbg", "h2g", "emg", "lsb", "rg", "galaxy", "g?", "pog", "ig", "lsb") -> Kind.GALAXY to "Galaxy"
                l in setOf("psr", "pulsar") -> Kind.PULSAR to "Pulsar"
                l in setOf("bh", "bh?", "xb", "lxb", "hxb", "xb*", "lxb*", "hxb*") -> Kind.BLACK_HOLE to "Black hole or X-ray binary"
                l in setOf("n*", "ns", "mag", "n*?") -> Kind.NEUTRON_STAR to "Neutron star"
                l in setOf("pn", "hii", "rnebula", "rne", "snr", "cld", "mol", "drkneb", "dne", "ism", "smm", "gne", "bub", "neb", "pn?", "snr?", "cor", "glb") || l.contains("neb") -> Kind.NEBULA to "Nebula"
                l.contains("planet") || l == "pl" || l == "pl?" -> Kind.PLANET to "Planet"
                l == "bd*" || l == "bd?" -> Kind.STAR to "Brown dwarf"
                l == "wd*" || l == "wd?" -> Kind.STAR to "White dwarf"
                l.contains("*") || l in setOf("star", "y*o", "y*?", "tt*", "ae*", "ce*", "rr*") -> Kind.STAR to "Star"
                else -> Kind.MARKER to (if (s.isEmpty()) "Object" else "Object ($s)")
            }
        }
    }
}

/** An asteroid or comet from the JPL Small-Body Database, on its real orbit. */
class NetSmallBody(
    val json: JSONObject,
    override val id: String,
    override val name: String,
    override val kind: Kind,
    private val a: Double, private val e: Double, private val i: Double, private val node: Double, private val peri: Double,
    private val m0: Double, private val epoch: Double,
    private val sub: String,
    private val extra: List<Pair<String, String>>,
    override val radius: Double,
) : Body() {
    override val source = Source.REAL
    override val color = if (kind == Kind.COMET) 0xBFE6FF else 0xB8A898
    override val subtitle get() = sub
    override fun viewDistance() = kotlin.math.max(radius * 6.0, 2.0e4)
    override fun position(jd: Double): UPos {
        val n = 0.01720209895 / (a * sqrt(a))
        val ecl = Astro.orbitToPlane(a * Units.AU, e, i, peri, node, m0 + n * (jd - epoch))
        return UPos.ZERO + Astro.eclipticToEquatorial(ecl)
    }

    override fun facts(jd: Double): List<Pair<String, String>> {
        val out = ArrayList(extra)
        out.add(1, "From the Sun now" to String.format(Locale.US, "%.2f AU", (position(jd) - UPos.ZERO).length() / Units.AU))
        return out
    }

    companion object {
        fun fromSbdb(o: JSONObject): NetSmallBody? {
            val obj = o.optJSONObject("object") ?: return null
            val orbit = o.optJSONObject("orbit") ?: return null
            val els = orbit.optJSONArray("elements") ?: return null
            val v = HashMap<String, Double>()
            for (k in 0 until els.length()) {
                val el = els.getJSONObject(k)
                el.optString("value").toDoubleOrNull()?.let { v[el.optString("name")] = it }
            }
            val phys = HashMap<String, Double>()
            o.optJSONArray("phys_par")?.let { arr ->
                for (k in 0 until arr.length()) {
                    val p = arr.getJSONObject(k)
                    p.optString("value").toDoubleOrNull()?.let { phys[p.optString("name")] = it }
                }
            }
            val j = JSONObject().put("t", "sb")
                .put("name", obj.optString("fullname").trim()).put("pdes", obj.optString("des"))
                .put("kind", obj.optString("kind")).put("cls", obj.optJSONObject("orbit_class")?.optString("name") ?: "")
                .put("epoch", orbit.optString("epoch").toDoubleOrNull() ?: return null)
            for ((k, x) in v) j.put("el_$k", x)
            for ((k, x) in phys) j.put("ph_$k", x)
            return fromJson(j)
        }

        fun fromJson(j: JSONObject): NetSmallBody? {
            fun el(k: String) = j.optDouble("el_$k", Double.NaN).takeIf { !it.isNaN() }
            val e = el("e") ?: return null
            val q = el("q")
            val a = el("a") ?: q?.let { it / (1 - e) } ?: return null
            if (e >= 1 || a <= 0) return null   // hyperbolic visitors: no closed orbit to draw
            val epoch = j.optDouble("epoch")
            val ma = el("ma")?.let { Math.toRadians(it) } ?: el("tp")?.let { tp -> 0.01720209895 / (a * sqrt(a)) * (epoch - tp) } ?: return null
            val comet = j.optString("kind").startsWith("c")
            val full = j.optString("name").trim()
            val name = if (comet) full else AsteroidDb.displayName(full)
            val diam = j.optDouble("ph_diameter", Double.NaN)
            val h = j.optDouble("ph_H", Double.NaN)
            val km = when {
                !diam.isNaN() -> diam
                !h.isNaN() -> 1329.0 / sqrt(0.14) * 10.0.pow(-0.2 * h)
                comet -> 5.0
                else -> 1.0
            }
            val facts = ArrayList<Pair<String, String>>()
            facts.add("Size" to if (!diam.isNaN()) String.format(Locale.US, "%,.1f km across", diam) else String.format(Locale.US, "about %,.2f km across (estimated)", km))
            facts.add("Orbit" to String.format(Locale.US, "%.2f–%.2f AU from the Sun, every %s", a * (1 - e), a * (1 + e), SolarBody.period(365.25 * a.pow(1.5))))
            facts.add("Tilt of orbit" to String.format(Locale.US, "%.1f°", el("i") ?: 0.0))
            if (!h.isNaN()) facts.add("Brightness (H)" to String.format(Locale.US, "%.1f", h))
            facts.add("Data" to "JPL Small-Body Database, looked up online")
            val cls = j.optString("cls").ifEmpty { if (comet) "Comet" else "Asteroid" }
            return NetSmallBody(
                j, "net:sb:" + j.optString("pdes", name), name, if (comet) Kind.COMET else Kind.ASTEROID,
                a, e, Math.toRadians(el("i") ?: 0.0), Math.toRadians(el("om") ?: 0.0), Math.toRadians(el("w") ?: 0.0),
                ma, epoch, cls, facts, km * 500.0,
            )
        }

    }
}
