package io.github.hansoda.trace.data

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * Web Mercator helpers. World coordinates run from 0 to 1 on both axes, x to the east and
 * y to the south, matching slippy-map tile numbering.
 */
object Geo {
    const val EARTH_RADIUS_METERS = 6_371_008.8
    const val EQUATOR_METERS = 40_075_016.686
    const val MAX_LATITUDE = 85.05112878

    fun x(lon: Double): Double = (lon + 180.0) / 360.0

    fun y(lat: Double): Double {
        val s = sin(Math.toRadians(lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)))
        return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
    }

    fun lon(x: Double): Double = x * 360.0 - 180.0

    fun lat(y: Double): Double = Math.toDegrees(atan(sinh(PI * (1 - 2 * y))))

    /** World units per meter on the ground at world latitude [y]. */
    fun worldPerMeter(y: Double): Double = cosh(PI * (1 - 2 * y)) / EQUATOR_METERS

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).let { it * it } + cos(p1) * cos(p2) * sin(dl / 2).let { it * it }
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
