package io.github.hansoda.trace.media

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import io.github.hansoda.trace.data.Geo
import io.github.hansoda.trace.data.Timeline
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Names the places photos were taken, with the phone's own geocoder. */
class Places(context: Context) {
    private val context = context.applicationContext

    /** False on phones without a geocoder, such as those without Google's services. */
    val available: Boolean get() = runCatching { Geocoder.isPresent() }.getOrDefault(false)

    /** A short name for where [latitude], [longitude] is, such as "Navy Pier, Chicago", in [locale]. */
    suspend fun name(latitude: Double, longitude: Double, locale: Locale): String? = withContext(Dispatchers.IO) {
        val geocoder = Geocoder(context, locale)
        val address = withTimeoutOrNull(TIMEOUT_MS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocation(
                        latitude, longitude, 1,
                        object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                            }

                            override fun onError(errorMessage: String?) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                        },
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull() }.getOrNull()
            }
        }
        address?.let(::label)
    }

    /** The landmark or neighbourhood, and the town, as far as the address says. */
    private fun label(address: Address): String? {
        val feature = address.featureName?.trim()?.takeIf { name ->
            // House numbers and whole street addresses make poor names.
            name.isNotEmpty() && name.any(Char::isLetter) && name != address.thoroughfare && name != address.getAddressLine(0)
        }
        val near = feature ?: address.subLocality?.takeIf { it.isNotBlank() } ?: address.thoroughfare?.takeIf { it.isNotBlank() }
        val town = address.locality?.takeIf { it.isNotBlank() } ?: address.subAdminArea?.takeIf { it.isNotBlank() } ?: address.adminArea
        return listOfNotNull(near, town).distinct().joinToString(", ").ifBlank { address.countryName }
    }

    companion object {
        private const val TIMEOUT_MS = 10_000L
        private const val HOUR = 3_600_000L

        /**
         * Where [timeline] was at [time]: the nearest fix within an hour, or the place of a stay
         * that lasted through it. Latitude and longitude, or null.
         */
        fun locate(timeline: Timeline, time: Long): Pair<Double, Double>? {
            val i = nearest(timeline, time) ?: return null
            if (abs(timeline.times[i] - time) <= HOUR) return timeline.latE7[i] / 1e7 to timeline.lonE7[i] / 1e7
            // Between two fixes in the same place: somewhere the trip stayed a while.
            val after = java.util.Arrays.binarySearch(timeline.times, time).let { if (it >= 0) it else -it - 1 }
            if (after <= 0 || after >= timeline.size) return null
            val before = after - 1
            val meters = Geo.haversineMeters(
                timeline.latE7[before] / 1e7, timeline.lonE7[before] / 1e7, timeline.latE7[after] / 1e7, timeline.lonE7[after] / 1e7,
            )
            return if (meters < 1_000) timeline.latE7[before] / 1e7 to timeline.lonE7[before] / 1e7 else null
        }

        /** The UTC offset in minutes where [timeline] was nearest [time], or [Timeline.NO_OFFSET]. */
        fun offset(timeline: Timeline?, time: Long): Short {
            val i = timeline?.let { nearest(it, time) } ?: return Timeline.NO_OFFSET
            return timeline.offsets[i]
        }

        private fun nearest(timeline: Timeline, time: Long): Int? {
            if (timeline.size == 0) return null
            val found = java.util.Arrays.binarySearch(timeline.times, time)
            if (found >= 0) return found
            val after = -found - 1
            return when {
                after == 0 -> 0
                after >= timeline.size -> timeline.size - 1
                time - timeline.times[after - 1] <= timeline.times[after] - time -> after - 1
                else -> after
            }
        }
    }
}
