package com.emotionalleaves.universe.engine

import android.content.Context
import com.emotionalleaves.universe.core.Rng
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tan

/** A nebula or star cluster from OpenNGC. Position real; distance curated or estimated. */
class DsoBody(
    override val id: String,
    override val name: String,
    val catalogName: String,
    val type: String,
    val pos: UPos,
    override val radius: Double,
    val mag: Double?,
    val messier: String,
    val common: String,
    val distLy: Double,
    val distanceEstimated: Boolean,
) : Body() {
    override val kind = if (isCluster) Kind.CLUSTER else Kind.NEBULA
    override val source = Source.REAL
    override fun position(jd: Double) = pos

    val isCluster get() = type == "OCl" || type == "GCl" || type == "*Ass"
    val hasNebula get() = type in setOf("HII", "EmN", "Neb", "RfN", "PN", "SNR", "Cl+N")

    override val subtitle: String
        get() = typeLabel(type) + when {
            messier.isNotEmpty() && name != "M$messier" -> " · M$messier"
            catalogName != name -> " · $catalogName"
            else -> ""
        }

    override fun viewDistance() = radius * 2.8

    override val color: Int
        get() = when (type) {
            "PN" -> 0x7FE8D8
            "RfN" -> 0x8AB0FF
            "SNR" -> 0xFF9A7A
            "OCl", "*Ass" -> 0xCFE0FF
            "GCl" -> 0xFFE6B8
            else -> 0xFF7A9A
        }

    override fun facts(jd: Double): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out.add("Type" to typeLabel(type))
        out.add("Distance" to String.format(Locale.US, "%,.0f ly%s", distLy, if (distanceEstimated) " (estimated)" else ""))
        out.add("Size" to String.format(Locale.US, "about %,.0f ly across", radius * 2 / Units.LY))
        if (mag != null) out.add("Brightness" to String.format(Locale.US, "magnitude %.1f", mag))
        val ids = listOfNotNull(messier.takeIf { it.isNotEmpty() }?.let { "M$it" }, catalogName.takeIf { it != name })
        if (ids.isNotEmpty()) out.add("Catalogue" to ids.joinToString(", "))
        out.add("Data" to "OpenNGC position and size" + if (distanceEstimated) "; distance generated from its type" else "")
        return out
    }

    /** Generated member stars for clusters, as galaxy-free positions (pc offsets) with abs magnitudes. */
    fun memberStars(): Pair<FloatArray, Int> {
        val rng = Rng(Rng.hashString(id))
        val globular = type == "GCl"
        val n = if (globular) 30_000 else (150 + rng.nextInt(650))
        val rPc = radius / Units.PC
        val core = rPc * if (globular) 0.12 else 0.35
        val data = FloatArray(n * 7)
        var k = 0
        val sunDist = distLy * Units.LY / Units.PC
        for (i in 0 until n) {
            // Plummer sphere
            val u = rng.nextDouble().coerceIn(1e-6, 0.999)
            val r = core / kotlin.math.sqrt(u.pow(-2.0 / 3.0) - 1).coerceAtLeast(1e-3)
            if (r > rPc * 1.5) continue
            val v = rng.unitVector() * r
            // Old clusters: red giants and dim dwarfs; young: hot blue stars.
            val m: Double
            val t: Double
            if (globular) {
                val g = rng.nextDouble()
                if (g < 0.04) { m = -1.0 + rng.nextDouble() * 2.5; t = 4000.0 + rng.nextDouble() * 800 } else {
                    m = 3.5 + rng.nextDouble() * 6; t = 5200.0 + rng.nextDouble() * 900
                }
            } else {
                val g = rng.nextDouble()
                if (g < 0.08) { m = -2.5 + rng.nextDouble() * 3.5; t = 9000.0 + rng.nextDouble() * 15000 } else {
                    m = 1.0 + rng.nextDouble() * 9; t = 3800.0 + rng.nextDouble() * 5000
                }
            }
            // Real stars win: skip members the real catalogue would already show.
            val app = m + 5 * log10(max(sunDist, 1.0)) - 5
            if (app < StarField.catalogLimit) continue
            val c = StarBody.tempColor(t)
            data[k * 7] = v.x.toFloat(); data[k * 7 + 1] = v.y.toFloat(); data[k * 7 + 2] = v.z.toFloat()
            data[k * 7 + 3] = c[0]; data[k * 7 + 4] = c[1]; data[k * 7 + 5] = c[2]; data[k * 7 + 6] = m.toFloat()
            k++
        }
        return data to k
    }

    companion object {
        fun typeLabel(t: String) = when (t) {
            "OCl" -> "Open star cluster"
            "GCl" -> "Globular star cluster"
            "Cl+N" -> "Star cluster with nebula"
            "PN" -> "Planetary nebula"
            "HII" -> "Emission nebula (star-forming region)"
            "EmN" -> "Emission nebula"
            "RfN" -> "Reflection nebula"
            "SNR" -> "Supernova remnant"
            "DrkN" -> "Dark nebula"
            "*Ass" -> "Stellar association"
            else -> "Nebula"
        }

        fun load(context: Context): List<DsoBody> {
            val out = ArrayList<DsoBody>()
            try {
                context.assets.open("dso/dso.tsv").bufferedReader().useLines { lines ->
                    for (l in lines.drop(1)) {
                        val p = l.split("\t")
                        if (p.size < 13) continue
                        val type = p[2]
                        if (type == "DrkN") continue
                        val ra = p[3].toDoubleOrNull() ?: continue
                        val dec = p[4].toDoubleOrNull() ?: continue
                        val maj = p[5].toDoubleOrNull() ?: 0.0
                        val dist = p[11].toDoubleOrNull() ?: continue
                        val pos = UPos.ZERO + Vec3d.fromRaDec(ra, dec) * (dist * Units.LY)
                        val angR = Math.toRadians(max(maj, 1.0) / 60.0 / 2)
                        val radius = tan(angR) * dist * Units.LY
                        val name = when {
                            p[10].isNotEmpty() -> p[10]
                            p[9].isNotEmpty() -> "M${p[9]}"
                            else -> p[0]
                        }
                        out.add(DsoBody("dso:${p[1]}", name, p[0], type, pos, radius, p[8].toDoubleOrNull(),
                            p[9], p[10], dist, p[12] == "G"))
                    }
                }
            } catch (_: Exception) {
            }
            return out
        }
    }
}
