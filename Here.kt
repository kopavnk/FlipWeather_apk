package org.example.flipweather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** "Follow my location": approximate position via the platform LocationManager (no Google Play services). */
object Here {
    fun hasPermission(c: Context) =
        c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Blocking: call off the main thread. Returns true if the stored position changed. */
    fun refresh(c: Context): Boolean {
        if (!Store.gps(c) || !hasPermission(c)) return false
        val lm = c.getSystemService(LocationManager::class.java) ?: return false

        var best: Location? = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }
            .maxByOrNull { it.time }

        // Last known fix older than 30 min (or none): ask for one fresh approximate fix (Android 11+).
        val stale = best == null || System.currentTimeMillis() - best.time > 30 * 60_000
        if (stale && Build.VERSION.SDK_INT >= 30) {
            val latch = CountDownLatch(1)
            var fresh: Location? = null
            try {
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, c.mainExecutor) { fresh = it; latch.countDown() }
                latch.await(10, TimeUnit.SECONDS)
            } catch (e: Exception) { }
            fresh?.let { best = it }
        }

        val l = best ?: return false
        val lat = Math.round(l.latitude * 100) / 100.0     // ~1 km, plenty for weather
        val lon = Math.round(l.longitude * 100) / 100.0
        val old = Store.gpsLoc(c)
        if (old != null) {
            val d = FloatArray(1)
            Location.distanceBetween(old.lat, old.lon, lat, lon, d)
            if (d[0] < 3000f) return false                  // hasn't moved: keep name and cache
        }
        Store.setGpsLoc(c, Loc(placeName(c, lat, lon) ?: old?.name ?: "Here", lat, lon))
        return true
    }

    @Suppress("DEPRECATION")
    private fun placeName(c: Context, lat: Double, lon: Double): String? = try {
        if (!Geocoder.isPresent()) null
        else Geocoder(c, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
            ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
    } catch (e: Exception) { null }
}
