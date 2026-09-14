package com.rfmapper.radio.android

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.content.Context
import com.rfmapper.core.model.IdentifierType
import com.rfmapper.core.model.SensorType
import com.rfmapper.core.model.ResultFreshness
import com.rfmapper.core.radio.Capability
import com.rfmapper.core.radio.Clock
import com.rfmapper.core.radio.LocationProvider
import com.rfmapper.core.radio.RadioSample
import com.rfmapper.core.radio.ScanProfile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * GNSS and network location for the *observer*.
 *
 * A coordinate recorded here says where the phone doing the observing was. It is never a claim about
 * an observed device, and the canonical schema keeps the two apart by construction
 * (`docs/02-observation-schema.md`).
 *
 * Deliberately built on the platform `LocationManager` rather than Google Play Services' fused
 * provider. The fused provider is better at pedestrian tracking outdoors, but it is proprietary,
 * unavailable on de-Googled and many rugged fleet devices, and — decisively for this system — it
 * blends Wi-Fi and cell inference into its fixes. Feeding a Wi-Fi-derived position back in as
 * independent evidence for a Wi-Fi positioning model is circular, and the circularity would be
 * invisible in the data.
 *
 * Indoors a GNSS fix is usually poor or absent, which is expected: the fix is recorded for outdoor
 * legs and site-level context, and [horizontalAccuracyMetres] is what tells the Lab how much to
 * trust it. An unusable fix is still recorded rather than dropped, because a 40-metre accuracy is a
 * fact about the environment.
 */
class AndroidLocationProvider(
    private val context: Context,
    private val profile: ScanProfile = ScanProfile.DEFAULT,
    private val clock: Clock = AndroidClock,
    /**
     * Minimum movement before a new fix is requested. Zero: a stationary observer's repeated fixes
     * are how GNSS noise gets characterised, and that characterisation is one of the assumptions
     * `docs/15` says must be measured rather than assumed.
     */
    private val minDistanceMetres: Float = 0f,
) : LocationProvider {

    private val locationManager: LocationManager?
        get() = context.applicationContext.getSystemService(Context.LOCATION_SERVICE)
            as? LocationManager

    override suspend fun capability(): Capability = RadioPermissions.locationCapability(context)

    @SuppressLint("MissingPermission")
    override fun samples(): Flow<RadioSample> = callbackFlow {
        val manager = locationManager
        if (manager == null || !RadioPermissions.locationCapability(context).live) {
            close()
            return@callbackFlow
        }

        val listener = LocationListener { location -> toSample(location)?.let(::trySend) }

        // The last known fix first, so a session starts with site context rather than waiting up to
        // a GNSS cold-start for it.
        for (provider in PROVIDERS) {
            runCatching { manager.getLastKnownLocation(provider) }
                .getOrNull()
                ?.let { location -> toSample(location, stale = true)?.let(::trySend) }
        }

        val registered = PROVIDERS.filter { provider ->
            runCatching {
                if (!manager.isProviderEnabled(provider)) return@runCatching false
                manager.requestLocationUpdates(
                    provider,
                    profile.locationIntervalMillis,
                    minDistanceMetres,
                    listener,
                    Looper.getMainLooper(),
                )
                true
            }.getOrDefault(false)
        }

        if (registered.isEmpty()) {
            // No provider would accept the request. Closing rather than hanging lets the session
            // record GNSS as degraded instead of silently waiting for a fix that cannot arrive.
            close()
            return@callbackFlow
        }

        awaitClose {
            runCatching { manager.removeUpdates(listener) }
        }
    }

    private fun toSample(location: Location, stale: Boolean = false): RadioSample? {
        val latitude = location.latitude
        val longitude = location.longitude
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        if (latitude.isNaN() || longitude.isNaN()) return null

        val monotonic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            location.elapsedRealtimeNanos / 1_000_000
        } else {
            clock.monotonicElapsedMillis()
        }
        val age = (clock.monotonicElapsedMillis() - monotonic).coerceAtLeast(0L)

        return RadioSample(
            sensorType = SensorType.GPS,
            // A fix is not a radio identity; the observer is the subject of its own fix.
            identifier = IdentifierType.GNSS_FIX.name,
            identifierType = IdentifierType.GNSS_FIX,
            wallClockMillis = location.time.takeIf { it > 0 } ?: clock.wallClockMillis(),
            monotonicElapsedMillis = monotonic,
            latitude = latitude,
            longitude = longitude,
            horizontalAccuracyMetres = if (location.hasAccuracy()) {
                location.accuracy.toDouble()
            } else {
                null
            },
            freshness = if (stale) ResultFreshness.CACHED else ResultFreshness.FRESH,
            resultAgeMillis = age,
            metadata = buildMap {
                put(KEY_LOCATION_PROVIDER, location.provider ?: "unknown")
                if (location.hasAltitude()) put(KEY_ALTITUDE_M, format(location.altitude))
                if (location.hasSpeed()) put(KEY_SPEED_MPS, format(location.speed.toDouble()))
                if (location.hasBearing()) put(KEY_BEARING_DEG, format(location.bearing.toDouble()))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    location.hasVerticalAccuracy()
                ) {
                    put(KEY_VERTICAL_ACCURACY_M, format(location.verticalAccuracyMeters.toDouble()))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    put(KEY_IS_MOCK, location.isMock.toString())
                }
            },
        )
    }

    private fun format(value: Double): String = String.format(java.util.Locale.ROOT, "%.3f", value)

    private companion object {
        /**
         * GPS first, then network. Both are consumed: the network provider's fix is coarse but
         * available indoors, and the two disagreeing is itself informative.
         */
        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

        const val KEY_LOCATION_PROVIDER = "location_provider"
        const val KEY_ALTITUDE_M = "altitude_m"
        const val KEY_SPEED_MPS = "speed_mps"
        const val KEY_BEARING_DEG = "bearing_deg"
        const val KEY_VERTICAL_ACCURACY_M = "vertical_accuracy_m"
        const val KEY_IS_MOCK = "is_mock_location"
    }
}
