package com.andrerinas.openheadunit.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.PermissionChecker
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.contract.LocationUpdateIntent
import com.andrerinas.openheadunit.utils.AppLog

class GpsLocation constructor(private val context: Context): LocationListener {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val settings by lazy { App.provide(context).settings }
    private var requested: Boolean = false
    private val handler = Handler(Looper.getMainLooper())

    // Only ever set from onLocationChanged, so a resend can never mislabel a network/passive
    // fallback fix as this head unit's real GPS.
    private var lastGpsFix: Location? = null

    // Taken from the monotonic clock rather than Location.time, because a resend deliberately
    // bumps that field and would otherwise keep declaring itself fresh.
    private var lastGpsFixAtMs: Long = 0L

    // When the subscription was made, so the first fix can be reported with how long it took.
    private var subscribedAtMs: Long = 0L

    private var staleFixReported = false
    private var lastHalReconnectAtMs: Long = 0L

    // Secondary fallback listener for Network / Passive providers
    private val fallbackListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!requested) return
            // Only use fallback location if real GPS fix is currently missing or older than threshold
            val now = SystemClock.elapsedRealtime()
            val primaryFixAgeMs = if (lastGpsFix != null) (now - lastGpsFixAtMs) else Long.MAX_VALUE
            if (GpsLocationPolicy.shouldAcceptFallback(lastGpsFix != null, primaryFixAgeMs)) {
                if (AppLog.LOG_VERBOSE) {
                    AppLog.v("GpsLocation: fallback fix received from ${location.provider}")
                }
                LocationHolder.update(location)
                context.sendBroadcast(LocationUpdateIntent(location).setPackage(context.packageName))
            }
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    // Android Auto prefers the car's GPS when advertised.
    // If the car's GPS stops emitting, Google Maps displays "Searching for GPS...".
    // 
    // This runnable fulfills two critical reliability functions:
    // 1. Resends the last known fix when parked/stationary to keep the AA stream alive.
    // 2. Acts as a HAL stall watchdog: if no updates arrive for >15s, kicks the vendor
    //    GPS HAL by re-subscribing, recovering from frequent AC8227L / automotive GPS stalls.
    private val periodicResend = object : Runnable {
        override fun run() {
            if (!requested) return

            val now = SystemClock.elapsedRealtime()
            val fix = lastGpsFix

            if (fix != null) {
                val ageMs = now - lastGpsFixAtMs
                if (GpsLocationPolicy.isFixStale(true, ageMs)) {
                    if (!staleFixReported) {
                        staleFixReported = true
                        AppLog.i("GpsLocation: no GPS fix for ${ageMs / 1000}s, stopping resend so the phone can fall back to its own location")
                    }
                } else if (GpsLocationPolicy.shouldResendFix(settings.useGpsForNavigation, true, ageMs)) {
                    val refreshed = Location(fix).apply {
                        time = System.currentTimeMillis()
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    }
                    context.sendBroadcast(LocationUpdateIntent(refreshed).setPackage(context.packageName))
                }
            }

            // Vendor HAL stall watchdog: if requested and no fix arrived for > STALL_RECOVERY_THRESHOLD_MS,
            // re-register with LocationManager to unstick Chinese head unit GPS daemons.
            val timeSinceFix = if (lastGpsFixAtMs > 0L) (now - lastGpsFixAtMs) else (now - subscribedAtMs)
            val timeSinceLastReconnect = now - lastHalReconnectAtMs
            if (GpsLocationPolicy.shouldTriggerHalRecovery(timeSinceFix, timeSinceLastReconnect)) {
                AppLog.w("GpsLocation: GPS updates stalled (${timeSinceFix / 1000}s without fix). Re-registering with LocationManager to kick hardware HAL...")
                reconnectProviders()
            }

            handler.postDelayed(this, GpsLocationPolicy.RESEND_INTERVAL_MS)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (requested) {
            return
        }
        AppLog.i("Request location updates")
        val hasPermission = PermissionChecker.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PermissionChecker.PERMISSION_GRANTED
        val providerEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        if (hasPermission && providerEnabled) {
            requested = true
            subscribedAtMs = SystemClock.elapsedRealtime()
            lastHalReconnectAtMs = subscribedAtMs

            // 1. Seed initial location immediately from last known GPS, LocationHolder, or settings.
            // This eliminates the 1-3 minute startup delay ("Searching for GPS...") while waiting for cold satellite lock.
            seedInitialLocation()

            // 2. Request updates from GPS_PROVIDER (Primary, 1Hz)
            requestProviders()

            handler.postDelayed(periodicResend, GpsLocationPolicy.RESEND_INTERVAL_MS)
        } else {
            AppLog.i("GpsLocation: not requesting updates, ACCESS_FINE_LOCATION granted=$hasPermission GPS_PROVIDER enabled=$providerEnabled")
        }
    }

    @SuppressLint("MissingPermission")
    private fun seedInitialLocation() {
        try {
            val osLastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val holderLast = LocationHolder.currentLocation(context)
            val settingsLast = settings.lastKnownLocation.takeIf { it.latitude != 0.0 && it.longitude != 0.0 }
            
            val seed = listOfNotNull(osLastGps, holderLast, settingsLast).maxByOrNull { it.time }
            if (seed != null) {
                AppLog.i("GpsLocation: Seeding initial location: lat=${seed.latitude}, lon=${seed.longitude}, provider=${seed.provider}")
                lastGpsFix = seed
                lastGpsFixAtMs = SystemClock.elapsedRealtime()
                LocationHolder.update(seed)
                val primedFix = Location(seed).apply {
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                context.sendBroadcast(LocationUpdateIntent(primedFix).setPackage(context.packageName))
            } else {
                AppLog.i("GpsLocation: No initial location seed available yet.")
            }
        } catch (e: Exception) {
            AppLog.w("GpsLocation: Failed to seed initial location: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestProviders() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, GpsLocationPolicy.UPDATE_INTERVAL_MS, 0f, this
                )
            }

            // Also register secondary fallback listener for Network and Passive providers if enabled
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, GpsLocationPolicy.UPDATE_INTERVAL_MS * 2, 0f, fallbackListener
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER, GpsLocationPolicy.UPDATE_INTERVAL_MS, 0f, fallbackListener
                )
            }
        } catch (e: Exception) {
            AppLog.e("GpsLocation: Error requesting location updates: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun reconnectProviders() {
        if (!requested) return
        lastHalReconnectAtMs = SystemClock.elapsedRealtime()
        try {
            locationManager.removeUpdates(this)
            locationManager.removeUpdates(fallbackListener)
        } catch (e: Exception) {
            AppLog.w("GpsLocation: Error removing updates during reconnect: ${e.message}")
        }
        requestProviders()
    }

    override fun onLocationChanged(location: Location) {
        // A callback dispatched before removeUpdates() took effect must not repopulate state a
        // stop() just cleared - the next start() would inherit a fix from the previous session.
        if (!requested) {
            return
        }
        // Once per subscription, and carrying no position: without it a head unit whose antenna is
        // not connected is indistinguishable from a working one in a log. It passes every gate in
        // start() - the provider exists and is enabled - and then simply never locks, so the only
        // other evidence is the absence of lines that were never going to appear. How long the
        // lock took separates a healthy chip from one that is barely hearing the satellites.
        // lastGpsFix is the flag: it is null exactly between start() and the first fix.
        if (lastGpsFix == null) {
            AppLog.i("GpsLocation: first fix after ${(SystemClock.elapsedRealtime() - subscribedAtMs) / 1000}s")
        }
        // Cadence only, never a position: this is what lets a parked verbose log show whether the
        // chip honours a time-only subscription (and so whether the resend backstop is needed).
        if (AppLog.LOG_VERBOSE) {
            AppLog.v("GpsLocation: fix received")
        }
        lastGpsFix = location
        lastGpsFixAtMs = SystemClock.elapsedRealtime()
        // Reset here rather than in the resend's fresh branch: on a healthy chip that branch never
        // runs, and the flag left latched would silence the stale line for every outage after the
        // first one in a session.
        staleFixReported = false
        LocationHolder.update(location)
        context.sendBroadcast(LocationUpdateIntent(location).setPackage(context.packageName))
    }

    override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {
        AppLog.i("$provider: $status")
    }

    override fun onProviderEnabled(provider: String) {
        AppLog.i(provider)
    }

    override fun onProviderDisabled(provider: String) {
        AppLog.i(provider)
    }

    fun stop() {
        AppLog.i("Remove location updates")
        requested = false
        // Every field start() depends on goes back to its initial value, so a re-armed instance
        // cannot resend a fix acquired during the previous connection.
        lastGpsFix = null
        lastGpsFixAtMs = 0L
        subscribedAtMs = 0L
        staleFixReported = false
        try {
            locationManager.removeUpdates(this)
            locationManager.removeUpdates(fallbackListener)
        } catch (e: Exception) {
            AppLog.w("GpsLocation: Error removing updates in stop(): ${e.message}")
        }
        handler.removeCallbacks(periodicResend)
    }
}
