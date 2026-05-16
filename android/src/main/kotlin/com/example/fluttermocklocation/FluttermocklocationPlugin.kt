package com.example.fluttermocklocation

import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.content.Context
import android.util.Log
import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.EventChannel
import android.os.Handler
import android.os.Looper

/**
 * FluttermocklocationPlugin — patched for stable continuous mock location.
 *
 * Patches vs. upstream 0.0.5:
 *   1. addTestProvider() is only called ONCE per process lifetime; further
 *      updateMockLocation() calls only do setTestProviderLocation(). The
 *      original threw IllegalArgumentException("Provider already exists")
 *      on every call after the first and silently swallowed it, so the
 *      location was effectively frozen to the first value.
 *   2. removeTestProvider() is attempted before addTestProvider() to clear
 *      stale provider state from previous app runs.
 *   3. Periodic re-push handler is started only once and re-armed cleanly
 *      via removeCallbacks(), so accumulated runnables can no longer pile
 *      up when the Flutter side pushes at high frequency.
 *   4. Accuracy is taken from the "accuracy" call argument (float, meters)
 *      instead of hardcoded 5f. Falls back to 1f if missing.
 *   5. SecurityException / IllegalArgumentException are now logged so the
 *      Flutter side can at least see in adb logcat why the mock failed.
 */
class FluttermocklocationPlugin: FlutterPlugin, MethodCallHandler {
    companion object {
        private const val TAG = "FluttermockLoc"
    }

    private lateinit var channel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private var eventSink: EventChannel.EventSink? = null
    private lateinit var context: Context
    private val handler = Handler(Looper.getMainLooper())
    private var updateInterval: Long = 5000

    private var providerInitialized = false
    private var lastLatitude = 0.0
    private var lastLongitude = 0.0
    private var lastAltitude = 0.0
    private var lastAccuracy = 1.0f

    private val mockLocationRunnable = object : Runnable {
        override fun run() {
            // Periodic re-push of the last known location. Only updates the
            // Location; never re-adds the provider.
            pushLocation(context, lastLatitude, lastLongitude, lastAltitude, lastAccuracy)
            handler.postDelayed(this, updateInterval)
        }
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "fluttermocklocation")
        channel.setMethodCallHandler(this)
        eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "fluttermocklocation_updates")
        eventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                eventSink = events
            }

            override fun onCancel(arguments: Any?) {
                eventSink = null
            }
        })
        context = flutterPluginBinding.applicationContext
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "getPlatformVersion" -> {
                result.success("Android ${android.os.Build.VERSION.RELEASE}")
            }
            "updateMockLocation" -> {
                lastLatitude  = call.argument<Double>("latitude")  ?: 0.0
                lastLongitude = call.argument<Double>("longitude") ?: 0.0
                lastAltitude  = call.argument<Double>("altitude")  ?: 0.0
                lastAccuracy  = (call.argument<Number>("accuracy")?.toFloat()) ?: 1.0f
                updateInterval = (call.argument<Number>("delay")?.toLong()) ?: 5000L

                // Setup provider exactly once.
                if (!providerInitialized) {
                    if (!initializeProvider(context)) {
                        result.error(
                            "MOCK_PROVIDER_INIT_FAILED",
                            "Could not initialize GPS mock provider. " +
                            "Check that this app is selected as the mock location " +
                            "app in Developer Options.",
                            null
                        )
                        return
                    }
                }

                // Push immediately.
                pushLocation(context, lastLatitude, lastLongitude, lastAltitude, lastAccuracy)

                // Restart periodic loop — cancel previous schedule so we
                // don't accumulate runnables on repeated calls.
                handler.removeCallbacks(mockLocationRunnable)
                handler.postDelayed(mockLocationRunnable, updateInterval)

                result.success(null)
            }
            "stopMockLocation" -> {
                handler.removeCallbacks(mockLocationRunnable)
                teardownProvider(context)
                result.success(null)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        handler.removeCallbacks(mockLocationRunnable)
        teardownProvider(context)
    }

    private fun initializeProvider(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // Try cleanup first — provider may still exist from a previous run.
        try {
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            // ignore — provider may not exist yet
        }
        return try {
            locationManager.addTestProvider(
                LocationManager.GPS_PROVIDER,
                false, false, false, false, true,
                true, true, 1, 2
            )
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            providerInitialized = true
            Log.i(TAG, "GPS mock provider initialized")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: app is not selected as mock location app", e)
            false
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "IllegalArgumentException during provider init", e)
            false
        }
    }

    private fun teardownProvider(context: Context) {
        if (!providerInitialized) return
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
        } catch (e: Exception) { /* ignore */ }
        try {
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) { /* ignore */ }
        providerInitialized = false
        Log.i(TAG, "GPS mock provider torn down")
    }

    private fun pushLocation(
        context: Context,
        latitude: Double,
        longitude: Double,
        altitude: Double,
        accuracy: Float
    ) {
        if (!providerInitialized) return
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            val mockLocation = Location(LocationManager.GPS_PROVIDER).apply {
                setLatitude(latitude)
                setLongitude(longitude)
                this.altitude = altitude
                this.accuracy = accuracy
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, mockLocation)

            eventSink?.success(mapOf(
                "latitude"  to latitude,
                "longitude" to longitude,
                "altitude"  to altitude,
                "accuracy"  to accuracy.toDouble()
            ))
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException pushing location — provider state lost?", e)
            providerInitialized = false   // force re-init on next call
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "IllegalArgumentException pushing location", e)
        }
    }
}
