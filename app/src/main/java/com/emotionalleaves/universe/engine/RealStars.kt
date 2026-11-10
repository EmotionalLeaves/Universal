package com.emotionalleaves.universe.engine

import android.content.Context
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import java.util.Locale

/** The real star catalogues (HYG in the APK, AT-HYG as a pack) plus names and constellations. */
class RealStars(
    val base: StarCatalog,
    initialInfo: Map<Int, StarInfo>,
    initialConstellations: List<Constellation>,
) {
    /** Names arrive after the star positions (see [loadNames]), so the sky shows up first. */
    private val info = java.util.concurrent.ConcurrentHashMap<Int, StarInfo>(initialInfo)
    @Volatile var constellations: List<Constellation> = initialConstellations
        private set
    @Volatile var packs: List<StarCatalog> = emptyList()
    /** Exoplanet host stars that are not in HYG (e.g. TRAPPIST-1), built from the archive. */
    @Volatile var exoHosts: StarCatalog? = null
    val hipToId = java.util.concurrent.ConcurrentHashMap<String, Int>()
    val hdToId = java.util.concurrent.ConcurrentHashMap<String, Int>()
    val nameToId = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** Named stars (proper names), for labels. */
    @Volatile var namedIds: List<Int> = emptyList()
        private set

    init { index() }

    /** Adds the names, catalogue numbers and constellation figures (slow to parse: done after the sky is up). */
    fun setNames(names: Map<Int, StarInfo>, cons: List<Constellation>) {
        info.putAll(names)
        index()
        constellations = cons
    }

    private fun index() {
        val named = ArrayList<Int>()
        for (s in info.values) {
            if (s.hip.isNotEmpty()) hipToId[s.hip] = s.id
            if (s.hd.isNotEmpty()) hdToId[s.hd] = s.id
            if (s.proper.isNotEmpty()) {
                nameToId[s.proper.lowercase(Locale.US)] = s.id
                named.add(s.id)
            }
            if (s.desig.isNotEmpty()) nameToId[StarInfo.prettyDesig(s.desig).lowercase(Locale.US)] = s.id
            if (s.gl.isNotEmpty()) nameToId[s.gl.lowercase(Locale.US)] = s.id
        }
        namedIds = named
    }

    fun info(id: Int): StarInfo? = info[id]

    /** Real Gaia DR3 stars streamed around the camera (see [GaiaStream]). */
    @Volatile var gaia: GaiaStream? = null

    /** Every Gaia star to G = 15, streamed in cells (see [GaiaTiles]). */
    @Volatile var tiles: GaiaTiles? = null

    fun allCatalogs(): List<StarCatalog> = listOfNotNull(base, exoHosts, gaia?.near, gaia?.bubble?.catalog) + packs + (tiles?.catalogs ?: emptyList())

    /** Adds names for stars from other sources (exoplanet hosts). Call before publishing them. */
    fun addInfo(extra: Collection<StarInfo>) {
        for (s in extra) info[s.id] = s
        for (s in extra) if (s.proper.isNotEmpty()) nameToId[s.proper.lowercase(Locale.US)] = s.id
    }

    /** Builds a selectable body for catalogue star [id]. */
    fun body(id: Int): StarBody? {
        for (c in allCatalogs()) {
            val (tile, i) = c.locate(id) ?: continue
            return bodyFrom(tile, i)
        }
        return null
    }

    fun bodyFrom(tile: StarTile, i: Int): StarBody {
        val id = tile.ids[i]
        val sids = tile.sids
        if (sids != null) {
            return GaiaStream.makeBody(sids[i], UPos.ZERO + tile.posPc(i) * Units.PC, tile.absMag(i), tile.teffs?.get(i)?.toDouble() ?: 5000.0, null)
        }
        val inf = info[id]
        val pos = UPos.ZERO + tile.posPc(i) * Units.PC
        val r = tile.data[i * 7 + 3]; val g = tile.data[i * 7 + 4]; val b = tile.data[i * 7 + 5]
        val extra = ArrayList<Pair<String, String>>()
        if (inf != null) {
            if (inf.proper.isNotEmpty() && inf.desig.isNotEmpty()) extra.add("Also called" to StarInfo.prettyDesig(inf.desig))
            val ids = listOfNotNull(
                inf.hip.takeIf { it.isNotEmpty() }?.let { "HIP $it" },
                inf.hd.takeIf { it.isNotEmpty() }?.let { "HD $it" },
                inf.gl.takeIf { it.isNotEmpty() },
            )
            if (ids.isNotEmpty()) extra.add("Catalogue" to ids.joinToString(", "))
        }
        extra.add("Data" to when {
            id >= 20_000_000 -> "NASA Exoplanet Archive host star"
            id >= 10_000_000 -> "AT-HYG (Tycho-2 + Gaia DR3)"
            else -> "HYG v4.4 (Hipparcos, Yale, Gliese)"
        })
        val teff = inf?.teff ?: 5800.0
        return StarBody(
            "hyg:$id", inf?.displayName() ?: "Star ${id}", pos, tile.absMag(i), teff,
            inf?.spect ?: "", Source.REAL, extra, StarBody.rgbInt(r, g, b),
        ).also { it.catalogId = id }
    }

    /** Nearest catalogue star to [p] within [maxPc] parsecs, as (catalog, tile, index). */
    fun nearest(p: Vec3d, maxPc: Double): Pair<StarTile, Int>? {
        val ppc = p / Units.PC
        var best: Pair<StarTile, Int>? = null
        var bestD = maxPc * maxPc
        for (c in allCatalogs()) for (tile in c.tiles) {
            val a = tile.anchorPc
            // Tiles are 200 pc cubes: skip ones that cannot contain a close star.
            if (kotlin.math.abs(a.x - ppc.x) > 101 + maxPc || kotlin.math.abs(a.y - ppc.y) > 101 + maxPc ||
                kotlin.math.abs(a.z - ppc.z) > 101 + maxPc) continue
            val d = tile.data
            for (i in 0 until tile.count) {
                val dx = a.x + d[i * 7] - ppc.x
                val dy = a.y + d[i * 7 + 1] - ppc.y
                val dz = a.z + d[i * 7 + 2] - ppc.z
                val q = dx * dx + dy * dy + dz * dz
                if (q < bestD) { bestD = q; best = tile to i }
            }
        }
        return best
    }

    companion object {
        /** Star positions only (fast): enough to draw the sky. */
        fun loadBase(context: Context): RealStars? {
            val base = StarFiles.open(context, "stars/hyg.bin")?.use { StarFiles.read(it, "HYG") } ?: return null
            return RealStars(base, emptyMap(), emptyList())
        }

        fun load(context: Context): RealStars? {
            val rs = loadBase(context) ?: return null
            loadNames(context, rs)
            return rs
        }

        /** Parses names and constellation figures into [rs]. */
        fun loadNames(context: Context, rs: RealStars) {
            val info = HashMap<Int, StarInfo>(rs.base.count * 2)
            try {
                context.assets.open("stars/hyg_names.tsv").bufferedReader().useLines { lines ->
                    lines.drop(1).forEach { l -> StarInfo.parse(l)?.let { info[it.id] = it } }
                }
            } catch (_: Exception) {
            }
            val cons = ArrayList<Constellation>()
            try {
                val hipMap = HashMap<String, Int>()
                for (s in info.values) if (s.hip.isNotEmpty()) hipMap[s.hip] = s.id
                context.assets.open("stars/constellations.tsv").bufferedReader().useLines { lines ->
                    lines.forEach { l ->
                        val p = l.split("\t")
                        if (p.size >= 3) {
                            val ids = ArrayList<Int>()
                            for (pair in p[2].split(" ").chunked(2)) {
                                if (pair.size < 2) continue
                                val a = hipMap[pair[0]] ?: continue
                                val b = hipMap[pair[1]] ?: continue
                                ids.add(a); ids.add(b)
                            }
                            cons.add(Constellation(p[0], p[1], ids))
                        }
                    }
                }
            } catch (_: Exception) {
            }
            rs.setNames(info, cons)
        }
    }
}

/** A constellation stick figure as pairs of catalogue star ids. */
class Constellation(val abbr: String, val name: String, val pairIds: List<Int>)
