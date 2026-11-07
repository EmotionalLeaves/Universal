package com.emotionalleaves.universe.engine

import android.content.Context
import com.emotionalleaves.universe.core.Noise3
import com.emotionalleaves.universe.core.Quatd
import com.emotionalleaves.universe.core.Rng
import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units
import com.emotionalleaves.universe.core.Vec3d
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The large-scale structure beyond the galaxy catalogues: galaxies generated along a seeded
 * filament field (ridged noise, ~150 Mpc cells) out to the edge of the observable universe.
 * Real catalogues win: generation starts where the catalogues run out.
 */
class CosmicWebData(
    val innerMpc: Double,
    /** 7 floats per point in Mpc (x, y, z, r, g, b, absMag). */
    val data: FloatArray,
) {
    val count get() = data.size / 7

    fun positionMpc(i: Int) = Vec3d(data[i * 7].toDouble(), data[i * 7 + 1].toDouble(), data[i * 7 + 2].toDouble())

    /** An enterable galaxy for generated point [i] (rules 1, 4 and 6). */
    fun galaxy(i: Int): GalaxyBody {
        val seed = Rng.hash(CosmicWeb.SEED, i.toLong(), innerMpc.toLong())
        val rng = Rng(seed)
        val p = positionMpc(i)
        val center = UPos.ZERO + p * Units.MPC
        val type = GalaxyType.fromHubble("", seed)
        val absMag = -19.8 + rng.gaussian() * 1.1
        val radiusKpc = (11.0 * 10.0.pow(-0.2 * (absMag + 21.0)) * (0.7 + rng.nextDouble() * 0.6)).coerceIn(0.6, 60.0)
        val n = rng.unitVector()
        val x = Vec3d.perpendicular(n)
        val frame = Quatd.fromBasis(x, n.cross(x).normalized(), n)
        val name = String.format(Locale.US, "GX-%04X-%04d", (seed ushr 48).toInt() and 0xFFFF, i % 10000)
        return GalaxyBody(
            "gal:web$i", name, center, frame, GalaxyModel.forGalaxy(type, radiusKpc, seed), radiusKpc * 1000,
            p.length() * 3.2616, Source.GENERATED,
            listOf(
                "Data" to "Generated beyond the catalogues (cosmic web v${CosmicWeb.VERSION})",
                "Light travel" to "Seen as it was long ago; positions are today's (comoving)",
            ),
            seed, absMag,
        )
    }
}

object CosmicWeb {
    const val SEED = 0x51DE5EEDL
    const val VERSION = 1
    /** Fixed so ids stay stable on every phone; quality only changes how many are drawn. */
    const val COUNT = 220_000
    val EDGE_MPC = Units.UNIVERSE_RADIUS / Units.MPC

    fun generate(innerMpc: Double): CosmicWebData {
        val rng = Rng(Rng.hash(SEED, VERSION.toLong(), innerMpc.toLong()))
        val noise = Noise3(SEED)
        val data = FloatArray(COUNT * 7)
        val r0 = innerMpc
        val r1 = EDGE_MPC
        var k = 0
        var guard = 0
        while (k < COUNT && guard < COUNT * 40) {
            guard++
            // Radius with pdf ~ r (more points nearby, each far point stands for more galaxies).
            val r = sqrt(rng.nextDouble() * (r1 * r1 - r0 * r0) + r0 * r0)
            val p = rng.unitVector() * r
            val s = 1.0 / 160.0
            val web = noise.ridged(p.x * s, p.y * s, p.z * s, 4)
            if (rng.nextDouble() > web * web * web * 1.6) continue
            val warm = rng.nextDouble().toFloat()
            data[k * 7] = p.x.toFloat(); data[k * 7 + 1] = p.y.toFloat(); data[k * 7 + 2] = p.z.toFloat()
            data[k * 7 + 3] = 0.85f + 0.15f * warm; data[k * 7 + 4] = 0.85f; data[k * 7 + 5] = 1.0f - 0.2f * warm
            data[k * 7 + 6] = (-21.0 - 2.5 * log10(r / r0) * 0.8).toFloat()
            k++
        }
        return CosmicWebData(innerMpc, if (k == COUNT) data else data.copyOf(k * 7))
    }
}

/** The optional GLADE+ galaxy pack: millions of real galaxies as points (Mpc). */
class GladePack(val data: FloatArray, val maxMpc: Double) {
    val count get() = data.size / 7

    companion object {
        const val PATH = "packs/galaxies-glade/glade.bin"

        fun load(context: Context, maxPoints: Int): GladePack? = try {
            val f = File(context.filesDir, PATH)
            if (!f.exists()) null else {
                val bytes = f.readBytes()
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val magic = ByteArray(4).also { b.get(it) }
                require(String(magic) == "UXGL")
                b.int
                val total = (bytes.size - 8) / 16
                val step = maxOf(1, (total + maxPoints - 1) / maxPoints)
                val n = total / step
                val out = FloatArray(n * 7)
                var maxD = 0.0
                for (i in 0 until n) {
                    b.position(8 + i * step * 16)
                    val x = b.float; val y = b.float; val z = b.float; val m = b.float
                    out[i * 7] = x; out[i * 7 + 1] = y; out[i * 7 + 2] = z
                    out[i * 7 + 3] = 0.92f; out[i * 7 + 4] = 0.9f; out[i * 7 + 5] = 1f
                    out[i * 7 + 6] = m - 2.5f * log10(step.toFloat())
                    val d = sqrt((x * x + y * y + z * z).toDouble())
                    if (d > maxD) maxD = d
                }
                GladePack(out, maxD)
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** The observable universe itself, so you can search for it and fly out to see it whole. */
class UniverseBody : Body() {
    override val id = "universe"
    override val name = "Observable universe"
    override val kind = Kind.UNIVERSE
    override val source = Source.REAL
    override val radius = Units.UNIVERSE_RADIUS
    override fun position(jd: Double) = UPos.ZERO
    override val subtitle get() = "Everything light has had time to reach us from"
    override fun viewDistance() = radius * 2.4
    override fun facts(jd: Double) = listOf(
        "Radius" to "about 46 billion light years (comoving)",
        "Age" to "13.8 billion years",
        "Galaxies" to "around 2 trillion (estimate)",
        "Edge" to "Cosmic microwave background, Planck satellite map",
        "Data" to "Nearby galaxies real; distant structure generated",
    )
}
