package com.adityaapte.virtualcockpit

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*

class MainActivity : ComponentActivity() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private lateinit var sensorManager: SensorManager
    private var pressureListener: SensorEventListener? = null
    private var accelerometerListener: SensorEventListener? = null
    private var magnetometerListener: SensorEventListener? = null
    private var rotationListener: SensorEventListener? = null

    // Sensor data
    private var gravity: FloatArray? = null
    private var geomagnetic: FloatArray? = null

    // Baro filtering + VSI
    private var baroAltSmoothM: Double? = null
    private var lastBaroAltM: Double? = null
    private var lastBaroTimeMs: Long? = null
    private var vsiSmoothFpm: Double? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // UI reacts automatically via state; startLocationUpdates is called from Compose
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        setContent {
            MaterialTheme {
                LocationScreen(
                    hasPermission = hasFineLocationPermission(),
                    onRequestPermission = { requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                    startUpdates = { startLocationUpdates(it) },
                    stopUpdates = { stopLocationUpdates() },
                    startBaro = { startBarometerUpdates(it) },
                    stopBaro = { stopBarometerUpdates() },
                    startHeading = { startHeadingUpdates(it) },
                    stopHeading = { stopHeadingUpdates() },
                    startAttitude = { startAttitudeUpdates(it) },
                    stopAttitude = { stopAttitudeUpdates() }
                )
            }
        }
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates(onUpdate: (NavData) -> Unit) {
        if (!hasFineLocationPermission()) return

        // Avoid starting twice
        if (locationCallback != null) return

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L // 1s updates for now
        )
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(0f)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                onUpdate(
                    NavData(
                        lat = loc.latitude,
                        lon = loc.longitude,
                        speedMps = loc.speed.toDouble(),      // GPS ground speed
                        bearingDeg = loc.bearing.toDouble(),  // GPS track
                        gpsAltitudeM = loc.altitude, // GPS altitude
                        accuracyM = loc.accuracy.toDouble()
                    )
                )
            }
        }

        locationCallback = callback
        fusedClient.requestLocationUpdates(request, callback, mainLooper)
    }

    private fun stopLocationUpdates() {
        val cb = locationCallback ?: return
        fusedClient.removeLocationUpdates(cb)
        locationCallback = null
    }

    private fun startBarometerUpdates(onUpdate: (pressureHpa: Double, baroAltM: Double, vsiFpm: Double) -> Unit) {
        // Avoid starting twice
        if (pressureListener != null) return

        val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (pressureSensor == null) {
            Log.w("VirtualCockpit", "No barometer sensor found on this device.")
            return
        }

        // Smoothing factors (tuned for nice cockpit feel)
        val altitudeAlpha = 0.04   // smooth altitude
        val vsiAlpha = 0.08   // smooth VSI

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val pressureHpa = event.values[0].toDouble()

                // Convert pressure to altitude (meters) using Android’s standard atmosphere helper
                val baroAltM = SensorManager.getAltitude(
                    SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
                    pressureHpa.toFloat()
                ).toDouble()

                val nowMs = SystemClock.elapsedRealtime()

                // Smooth altitude first (EMA)
                baroAltSmoothM = when (val prev = baroAltSmoothM) {
                    null -> baroAltM
                    else -> prev + altitudeAlpha * (baroAltM - prev)
                }

                val altUsed = baroAltSmoothM ?: baroAltM

                // Compute VSI from smoothed altitude derivative
                val lastT = lastBaroTimeMs
                val lastA = lastBaroAltM
                if (lastT != null && lastA != null) {
                    val dt = (nowMs - lastT).coerceAtLeast(1).toDouble() / 1000.0
                    val vsMps = (altUsed - lastA) / dt
                    val vsiFpmRaw = vsMps * 196.8504 // m/s -> ft/min
                    val deadband = 30.0 // ft/min
                    val vsiFpmDb = if (kotlin.math.abs(vsiFpmRaw) < deadband) 0.0 else vsiFpmRaw

                    // Smooth VSI (EMA)
                    vsiSmoothFpm = when (val prev = vsiSmoothFpm) {
                        null -> vsiFpmDb
                        else -> prev + vsiAlpha * (vsiFpmDb - prev)
                    }
                }

                lastBaroTimeMs = nowMs
                lastBaroAltM = altUsed

                onUpdate(
                    pressureHpa,
                    altUsed,
                    vsiSmoothFpm ?: 0.0
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        pressureListener = listener
        sensorManager.registerListener(listener, pressureSensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopBarometerUpdates() {
        val l = pressureListener ?: return
        sensorManager.unregisterListener(l)
        pressureListener = null

        // Optional: keep smooth values for continuity, or reset. I recommend keeping them.
    }

    private fun startHeadingUpdates(onUpdate: (heading: Double) -> Unit) {
        if (accelerometerListener != null || magnetometerListener != null) return

        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        if (accelerometer == null || magnetometer == null) {
            Log.w("VirtualCockpit", "Required sensors for heading not found.")
            return
        }

        val accelListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                gravity = event.values
                updateHeading(onUpdate)
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }

        val magListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                geomagnetic = event.values
                updateHeading(onUpdate)
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }

        accelerometerListener = accelListener
        magnetometerListener = magListener
        sensorManager.registerListener(accelListener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(magListener, magnetometer, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopHeadingUpdates() {
        accelerometerListener?.let { sensorManager.unregisterListener(it) }
        magnetometerListener?.let { sensorManager.unregisterListener(it) }
        accelerometerListener = null
        magnetometerListener = null
    }

    private fun updateHeading(onUpdate: (heading: Double) -> Unit) {
        if (gravity != null && geomagnetic != null) {
            val r = FloatArray(9)
            val i = FloatArray(9)
            val success = SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)
            if (success) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(r, orientation)
                val azimuth = orientation[0]
                val heading = Math.toDegrees(azimuth.toDouble())
                onUpdate(heading)
            }
        }
    }

    private fun startAttitudeUpdates(onUpdate: (pitch: Double, roll: Double) -> Unit) {
        if (rotationListener != null) return

        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor == null) {
            Log.w("VirtualCockpit", "No rotation vector sensor found.")
            return
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation)

                val pitch = Math.toDegrees(orientation[1].toDouble())
                val roll = Math.toDegrees(orientation[2].toDouble())
                onUpdate(pitch, roll)
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }

        rotationListener = listener
        sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopAttitudeUpdates() {
        rotationListener?.let { sensorManager.unregisterListener(it) }
        rotationListener = null
    }
}

data class NavData(
    val lat: Double? = null,
    val lon: Double? = null,
    val speedMps: Double? = null,
    val bearingDeg: Double? = null,
    val gpsAltitudeM: Double? = null,
    val accuracyM: Double? = null,

    val pressureHpa: Double? = null,
    val baroAltitudeM: Double? = null,
    val vsiFpm: Double? = null,
    val headingDeg: Double? = null,
    val pitchDeg: Double? = null,
    val rollDeg: Double? = null
)

@Composable
private fun LocationScreen(
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    startUpdates: ((NavData) -> Unit) -> Unit,
    stopUpdates: () -> Unit,
    startBaro: (onUpdate: (pressureHpa: Double, baroAltM: Double, vsiFpm: Double) -> Unit) -> Unit,
    stopBaro: () -> Unit,
    startHeading: (onUpdate: (heading: Double) -> Unit) -> Unit,
    stopHeading: () -> Unit,
    startAttitude: (onUpdate: (pitch: Double, roll: Double) -> Unit) -> Unit,
    stopAttitude: () -> Unit
) {
    var nav by remember { mutableStateOf(NavData()) }

    // Start/stop updates based on permission + lifecycle of this screen
    DisposableEffect(hasPermission) {
        // Always try barometer and heading (no permission required)
        startBaro { pressure, baroAltM, vsiFpm ->
            nav = nav.copy(
                pressureHpa = pressure,
                baroAltitudeM = baroAltM,
                vsiFpm = vsiFpm
            )
        }
        startHeading { heading ->
            nav = nav.copy(headingDeg = heading)
        }
        startAttitude { pitch, roll ->
            nav = nav.copy(pitchDeg = pitch, rollDeg = roll)
        }

        if (hasPermission) {
            startUpdates { newNav ->
                nav = newNav.copy(
                    pressureHpa = nav.pressureHpa,
                    baroAltitudeM = nav.baroAltitudeM,
                    vsiFpm = nav.vsiFpm,
                    headingDeg = nav.headingDeg,
                    pitchDeg = nav.pitchDeg,
                    rollDeg = nav.rollDeg
                )
            }
        }

        onDispose {
            stopUpdates()
            stopBaro()
            stopHeading()
            stopAttitude()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        if (!hasPermission) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Location permission is required to show GPS data.")
                Button(onClick = onRequestPermission) { Text("Grant Location Permission") }
            }
        } else {
            CockpitScreen(nav)
        }
    }
}
