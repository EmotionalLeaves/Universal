package com.emotionalleaves.universe.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Time and orbital mechanics. The universe frame is equatorial J2000 (ICRS-like):
 * +X toward RA 0h, +Z toward the north celestial pole, origin at the Sun, units metres.
 */
object Astro {
    const val J2000 = 2451545.0
    private val OBLIQUITY = Math.toRadians(23.43928)
    private val COS_E = cos(OBLIQUITY)
    private val SIN_E = sin(OBLIQUITY)

    fun julianDate(epochMillis: Long): Double = epochMillis / 86_400_000.0 + 2440587.5
    fun epochMillis(jd: Double): Long = ((jd - 2440587.5) * 86_400_000.0).toLong()

    /** Ecliptic J2000 to equatorial J2000. */
    fun eclipticToEquatorial(v: Vec3d) = Vec3d(v.x, COS_E * v.y - SIN_E * v.z, SIN_E * v.y + COS_E * v.z)

    /** Solves Kepler's equation M = E - e sin E (radians). */
    fun solveKepler(m: Double, e: Double): Double {
        var mm = m % (2 * Math.PI)
        if (mm > Math.PI) mm -= 2 * Math.PI
        if (mm < -Math.PI) mm += 2 * Math.PI
        var ea = if (e < 0.8) mm else Math.PI * (if (mm < 0) -1 else 1)
        for (i in 0 until 30) {
            val d = (ea - e * sin(ea) - mm) / (1 - e * cos(ea))
            ea -= d
            if (abs(d) < 1e-12) break
        }
        return ea
    }

    /**
     * Position in the orbital plane rotated into the reference plane.
     * Angles in radians: inclination i, argument of periapsis w, ascending node node.
     */
    fun orbitToPlane(a: Double, e: Double, i: Double, w: Double, node: Double, meanAnomaly: Double): Vec3d {
        val ea = solveKepler(meanAnomaly, e)
        val xp = a * (cos(ea) - e)
        val yp = a * sqrt(1 - e * e) * sin(ea)
        val cw = cos(w); val sw = sin(w); val cn = cos(node); val sn = sin(node); val ci = cos(i); val si = sin(i)
        return Vec3d(
            (cw * cn - sw * sn * ci) * xp + (-sw * cn - cw * sn * ci) * yp,
            (cw * sn + sw * cn * ci) * xp + (-sw * sn + cw * cn * ci) * yp,
            (sw * si) * xp + (cw * si) * yp,
        )
    }

    /** JPL "Approximate Positions of the Planets", Table 1 (valid 1800-2050) plus Pluto. */
    class Elements(
        val a: Double, val e: Double, val i: Double, val l: Double, val peri: Double, val node: Double,
        val da: Double, val de: Double, val di: Double, val dl: Double, val dperi: Double, val dnode: Double,
    ) {
        /** Heliocentric equatorial position in metres at Julian date [jd]. */
        fun position(jd: Double): Vec3d {
            val t = (jd - J2000) / 36525.0
            val aa = a + da * t
            val ee = e + de * t
            val ii = Math.toRadians(i + di * t)
            val ll = l + dl * t
            val pp = peri + dperi * t
            val nn = node + dnode * t
            val w = Math.toRadians(pp - nn)
            val m = Math.toRadians(ll - pp)
            val ecl = orbitToPlane(aa * Units.AU, ee, ii, w, Math.toRadians(nn), m)
            return eclipticToEquatorial(ecl)
        }

        /** Orbital period in days. */
        fun periodDays(): Double = 365.25 * a.pow(1.5)
    }

    val MERCURY = Elements(0.38709927, 0.20563593, 7.00497902, 252.25032350, 77.45779628, 48.33076593,
        0.00000037, 0.00001906, -0.00594749, 149472.67411175, 0.16047689, -0.12534081)
    val VENUS = Elements(0.72333566, 0.00677672, 3.39467605, 181.97909950, 131.60246718, 76.67984255,
        0.00000390, -0.00004107, -0.00078890, 58517.81538729, 0.00268329, -0.27769418)
    val EM_BARY = Elements(1.00000261, 0.01671123, -0.00001531, 100.46457166, 102.93768193, 0.0,
        0.00000562, -0.00004392, -0.01294668, 35999.37244981, 0.32327364, 0.0)
    val MARS = Elements(1.52371034, 0.09339410, 1.84969142, -4.55343205, -23.94362959, 49.55953891,
        0.00001847, 0.00007882, -0.00813131, 19140.30268499, 0.44441088, -0.29257343)
    val JUPITER = Elements(5.20288700, 0.04838624, 1.30439695, 34.39644051, 14.72847983, 100.47390909,
        -0.00011607, -0.00013253, -0.00183714, 3034.74612775, 0.21252668, 0.20469106)
    val SATURN = Elements(9.53667594, 0.05386179, 2.48599187, 49.95424423, 92.59887831, 113.66242448,
        -0.00125060, -0.00050991, 0.00193609, 1222.49362201, -0.41897216, -0.28867794)
    val URANUS = Elements(19.18916464, 0.04725744, 0.77263783, 313.23810451, 170.95427630, 74.01692503,
        -0.00196176, -0.00004397, -0.00242939, 428.48202785, 0.40805281, 0.04240589)
    val NEPTUNE = Elements(30.06992276, 0.00859048, 1.77004347, -55.12002969, 44.96476227, 131.78422574,
        0.00026291, 0.00005105, 0.00035372, 218.45945325, -0.32241464, -0.00508664)
    val PLUTO = Elements(39.48211675, 0.24882730, 17.14001206, 238.92903833, 224.06891629, 110.30393684,
        -0.00031596, 0.00005170, 0.00004818, 145.20780515, -0.04062942, -0.01183482)

    /** Geocentric Moon position (equatorial, metres), low-precision Astronomical Almanac formula. */
    fun moonGeocentric(jd: Double): Vec3d {
        val d = jd - J2000
        val l0 = Math.toRadians(218.316 + 13.176396 * d)
        val m = Math.toRadians(134.963 + 13.064993 * d)
        val f = Math.toRadians(93.272 + 13.229350 * d)
        val lon = l0 + Math.toRadians(6.289) * sin(m)
        val lat = Math.toRadians(5.128) * sin(f)
        val dist = (385001.0 - 20905.0 * cos(m)) * 1000.0
        val ecl = Vec3d(dist * cos(lat) * cos(lon), dist * cos(lat) * sin(lon), dist * sin(lat))
        return eclipticToEquatorial(ecl)
    }

    /** Greenwich mean sidereal time in radians. */
    fun gmst(jd: Double): Double {
        val d = jd - J2000
        val deg = 280.46061837 + 360.98564736629 * d
        return Math.toRadians(((deg % 360) + 360) % 360)
    }

    /** Planet or moon orientation from IAU pole RA/Dec and a spin angle about that pole. */
    fun bodyOrientation(poleRaDeg: Double, poleDecDeg: Double, spin: Double): Quatd {
        val pole = Vec3d.fromRaDec(poleRaDeg, poleDecDeg)
        // Node of the body's equator on the celestial equator: Z x pole
        var node = Vec3d.Z.cross(pole)
        if (node.lengthSq() < 1e-12) node = Vec3d.X
        node = node.normalized()
        val yAxis = pole.cross(node).normalized()
        val base = Quatd.fromBasis(node, yAxis, pole)
        return base * Quatd.axisAngle(Vec3d.Z, spin)
    }

    /** Angle of a direction around an axis, helper for longitude readouts. */
    fun longitudeDeg(local: Vec3d): Double = Math.toDegrees(atan2(local.y, local.x))
}
