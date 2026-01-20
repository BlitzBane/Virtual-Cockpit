package com.adityaapte.virtualcockpit

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.*
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import java.util.concurrent.Executors
import org.maplibre.android.MapLibre


class MainActivity : ComponentActivity() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager

    private var locationCallback: LocationCallback? = null

    private var pressureListener: SensorEventListener? = null
    private var gyroListener: SensorEventListener? = null
    private var accelListener: SensorEventListener? = null
    private var gravityListener: SensorEventListener? = null
    private var linAccListener: SensorEventListener? = null
    private var magListener: SensorEventListener? = null
    private var gameRotListener: SensorEventListener? = null

    private var gnssCallback: GnssStatus.Callback? = null

    private val _nav = MutableStateFlow(NavData())
    val nav: StateFlow<NavData> = _nav

    // Baro smoothing + VSI
    private var baroAltSmoothM: Double? = null
    private var lastBaroAltM: Double? = null
    private var lastBaroTimeMs: Long? = null
    private var vsiSmoothFpm: Double? = null

    // Attitude calibration
    private var calibInv: FloatArray? = null

    // Demo mode
    private var demoJob: Job? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* UI reacts */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(applicationContext)

        // Allow Compose to handle insets (cutout/status bar)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            AirportRepository.get(this@MainActivity).ensureSeededFromAssets()
        }


        setContent {
            MaterialTheme {
                Surface {
                    AppRoot(
                        navFlow = nav,
                        hasPermission = hasFineLocationPermission(),
                        onRequestPermission = {
                            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        },
                        onStart = { startAll() },
                        onStop = { stopAll() },
                        onCalibrateAttitude = { calibrateAttitudeNow() },
                        onToggleDemo = { enabled ->
                            if (enabled) startDemo() else stopDemo()
                        },
                        isDemo = nav.value.isDemo
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startAll()
    }

    override fun onPause() {
        super.onPause()
        stopAll()
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun startAll() {
        if (nav.value.isDemo) return
        startBarometer()
        startGyro()
        startAccel()
        startGravity()
        startLinearAccel()
        startMag()
        startGameRotationAttitude()
        startGnss()
        startLocation()
    }

    private fun stopAll() {
        stopLocation()
        stopGnss()
        stopBarometer()
        stopGyro()
        stopAccel()
        stopGravity()
        stopLinearAccel()
        stopMag()
        stopGameRotationAttitude()
    }

    // ---- DEMO MODE ----
    private fun startDemo() {
        stopAll()
        demoJob?.cancel()
        demoJob = CoroutineScope(Dispatchers.Main).launch {
            val start = SystemClock.elapsedRealtime()
            while (isActive) {
                val t = (SystemClock.elapsedRealtime() - start) / 1000.0
                // Simple circle path near a fixed point (Chennai-ish placeholder)
                val baseLat = 13.0827
                val baseLon = 80.2707
                val r = 0.02 // ~2km
                val lat = baseLat + r * cos(t / 30.0)
                val lon = baseLon + r * sin(t / 30.0)

                val track = (t * 3.0) % 360.0
                val speed = 120.0 / 1.943844 // 120kt in m/s approx
                val altM = 1200.0 + 50.0 * sin(t / 10.0)
                val pitch = 2.0 * sin(t / 6.0)
                val roll = 15.0 * sin(t / 4.0)
                val vsi = 300.0 * cos(t / 10.0) // fpm-ish

                _nav.update {
                    it.copy(
                        lat = lat,
                        lon = lon,
                        speedMps = speed,
                        trackDeg = track,
                        gpsAltitudeM = altM,
                        accuracyM = 5.0,
                        pressureHpa = 1010.0,
                        baroAltitudeM = altM,
                        vsiFpm = vsi,
                        pitchDeg = pitch,
                        rollDeg = roll,
                        turnRateDps = 0.0,
                        satsInView = 18,
                        satsUsed = 12,
                        provider = "DEMO",
                        fixTimeMs = System.currentTimeMillis(),
                        isDemo = true
                    )
                }

                delay(200L)
            }
        }
    }

    private fun stopDemo() {
        demoJob?.cancel()
        demoJob = null
        _nav.update { it.copy(isDemo = false) }
        startAll()
    }

    // ---- LOCATION ----
    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!hasFineLocationPermission()) return
        if (locationCallback != null) return

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L
        )
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(0f)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                _nav.update {
                    it.copy(
                        lat = loc.latitude,
                        lon = loc.longitude,
                        speedMps = loc.speed.toDouble(),
                        trackDeg = loc.bearing.toDouble(),
                        gpsAltitudeM = loc.altitude,
                        accuracyM = loc.accuracy.toDouble(),
                        verticalAccuracyM = if (android.os.Build.VERSION.SDK_INT >= 26) loc.verticalAccuracyMeters.toDouble() else null,
                        speedAccuracyMps = if (android.os.Build.VERSION.SDK_INT >= 26) loc.speedAccuracyMetersPerSecond.toDouble() else null,
                        bearingAccuracyDeg = if (android.os.Build.VERSION.SDK_INT >= 26) loc.bearingAccuracyDegrees.toDouble() else null,
                        provider = loc.provider,
                        fixTimeMs = loc.time
                    )
                }
            }
        }

        locationCallback = callback
        fusedClient.requestLocationUpdates(request, callback, mainLooper)
    }

    private fun stopLocation() {
        val cb = locationCallback ?: return
        fusedClient.removeLocationUpdates(cb)
        locationCallback = null
    }

    // ---- GNSS SATS ----
    @SuppressLint("MissingPermission")
    private fun startGnss() {
        if (!hasFineLocationPermission()) return
        if (gnssCallback != null) return

        val cb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var inView = 0
                var used = 0
                for (i in 0 until status.satelliteCount) {
                    inView++
                    if (status.usedInFix(i)) used++
                }
                _nav.update { it.copy(satsInView = inView, satsUsed = used) }
            }
        }
        gnssCallback = cb
        locationManager.registerGnssStatusCallback(ContextCompat.getMainExecutor(this), cb)

    }

    private fun stopGnss() {
        val cb = gnssCallback ?: return
        locationManager.unregisterGnssStatusCallback(cb)
        gnssCallback = null
    }

    // ---- BAROMETER -> VSI ----
    private fun startBarometer() {
        if (pressureListener != null) return
        val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return

        val altitudeAlpha = 0.04
        val vsiAlpha = 0.08
        val deadbandFpm = 30.0

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val pressureHpa = event.values[0].toDouble()
                val baroAltM = SensorManager.getAltitude(
                    SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
                    pressureHpa.toFloat()
                ).toDouble()

                val nowMs = SystemClock.elapsedRealtime()

                baroAltSmoothM = when (val prev = baroAltSmoothM) {
                    null -> baroAltM
                    else -> prev + altitudeAlpha * (baroAltM - prev)
                }
                val altUsed = baroAltSmoothM ?: baroAltM

                val lastT = lastBaroTimeMs
                val lastA = lastBaroAltM
                if (lastT != null && lastA != null) {
                    val dt = ((nowMs - lastT).coerceAtLeast(1)).toDouble() / 1000.0
                    val vsMps = (altUsed - lastA) / dt
                    val vsiRaw = vsMps * 196.8504 // m/s -> ft/min
                    val vsiDb = if (abs(vsiRaw) < deadbandFpm) 0.0 else vsiRaw

                    vsiSmoothFpm = when (val prev = vsiSmoothFpm) {
                        null -> vsiDb
                        else -> prev + vsiAlpha * (vsiDb - prev)
                    }
                }

                lastBaroTimeMs = nowMs
                lastBaroAltM = altUsed

                _nav.update {
                    it.copy(
                        pressureHpa = pressureHpa,
                        baroAltitudeM = altUsed,
                        vsiFpm = vsiSmoothFpm ?: 0.0
                    )
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        pressureListener = listener
        sensorManager.registerListener(listener, pressureSensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopBarometer() {
        val l = pressureListener ?: return
        sensorManager.unregisterListener(l)
        pressureListener = null
    }

    // ---- GYRO XYZ + TURN RATE ----
    private fun startGyro() {
        if (gyroListener != null) return
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val gx = event.values[0].toDouble()
                val gy = event.values[1].toDouble()
                val gz = event.values[2].toDouble()
                val turnDeg = gz * (180.0 / Math.PI)

                _nav.update {
                    it.copy(
                        gyroX = gx, gyroY = gy, gyroZ = gz,
                        turnRateDps = turnDeg
                    )
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        gyroListener = listener
        sensorManager.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopGyro() {
        val l = gyroListener ?: return
        sensorManager.unregisterListener(l)
        gyroListener = null
    }

    private fun startAccel() {
        if (accelListener != null) return
        val s = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                _nav.update { it.copy(accelX = e.values[0].toDouble(), accelY = e.values[1].toDouble(), accelZ = e.values[2].toDouble()) }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        accelListener = l
        sensorManager.registerListener(l, s, SensorManager.SENSOR_DELAY_GAME)
    }
    private fun stopAccel() { accelListener?.let { sensorManager.unregisterListener(it) }; accelListener = null }

    private fun startGravity() {
        if (gravityListener != null) return
        val s = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                _nav.update { it.copy(gravityX = e.values[0].toDouble(), gravityY = e.values[1].toDouble(), gravityZ = e.values[2].toDouble()) }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        gravityListener = l
        sensorManager.registerListener(l, s, SensorManager.SENSOR_DELAY_GAME)
    }
    private fun stopGravity() { gravityListener?.let { sensorManager.unregisterListener(it) }; gravityListener = null }

    private fun startLinearAccel() {
        if (linAccListener != null) return
        val s = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                _nav.update { it.copy(linAccX = e.values[0].toDouble(), linAccY = e.values[1].toDouble(), linAccZ = e.values[2].toDouble()) }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        linAccListener = l
        sensorManager.registerListener(l, s, SensorManager.SENSOR_DELAY_GAME)
    }
    private fun stopLinearAccel() { linAccListener?.let { sensorManager.unregisterListener(it) }; linAccListener = null }

    private fun startMag() {
        if (magListener != null) return
        val s = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                _nav.update { it.copy(magX = e.values[0].toDouble(), magY = e.values[1].toDouble(), magZ = e.values[2].toDouble()) }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        magListener = l
        sensorManager.registerListener(l, s, SensorManager.SENSOR_DELAY_GAME)
    }
    private fun stopMag() { magListener?.let { sensorManager.unregisterListener(it) }; magListener = null }

    // ---- ATTITUDE (GAME_ROTATION_VECTOR) + CALIBRATION ----
    private fun startGameRotationAttitude() {
        if (gameRotListener != null) return
        val rot = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: return

        val listener = object : SensorEventListener {
            private val rotMat = FloatArray(9)
            private val rel = FloatArray(9)

            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotMat, event.values)
                val calib = calibInv
                if (calib == null) {
                    val pr = pitchRollFromMatrix(rotMat)
                    _nav.update { it.copy(pitchDeg = pr.first, rollDeg = pr.second) }
                    return
                }
                multiply3x3(calib, rotMat, rel)
                val pr = pitchRollFromMatrix(rel)
                _nav.update { it.copy(pitchDeg = pr.first, rollDeg = pr.second) }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        gameRotListener = listener
        sensorManager.registerListener(listener, rot, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopGameRotationAttitude() {
        val l = gameRotListener ?: return
        sensorManager.unregisterListener(l)
        gameRotListener = null
    }

    private fun calibrateAttitudeNow() {
        val rot = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: return
        val oneShot = object : SensorEventListener {
            private val m = FloatArray(9)
            private val inv = FloatArray(9)
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(m, event.values)
                invert3x3(m, inv)
                calibInv = inv
                sensorManager.unregisterListener(this)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(oneShot, rot, SensorManager.SENSOR_DELAY_FASTEST)
    }

    // ---- 3x3 helpers ----
    private fun multiply3x3(a: FloatArray, b: FloatArray, out: FloatArray) {
        out[0] = a[0]*b[0] + a[1]*b[3] + a[2]*b[6]
        out[1] = a[0]*b[1] + a[1]*b[4] + a[2]*b[7]
        out[2] = a[0]*b[2] + a[1]*b[5] + a[2]*b[8]
        out[3] = a[3]*b[0] + a[4]*b[3] + a[5]*b[6]
        out[4] = a[3]*b[1] + a[4]*b[4] + a[5]*b[7]
        out[5] = a[3]*b[2] + a[4]*b[5] + a[5]*b[8]
        out[6] = a[6]*b[0] + a[7]*b[3] + a[8]*b[6]
        out[7] = a[6]*b[1] + a[7]*b[4] + a[8]*b[7]
        out[8] = a[6]*b[2] + a[7]*b[5] + a[8]*b[8]
    }

    private fun invert3x3(m: FloatArray, out: FloatArray) {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val A = (e*i - f*h)
        val B = -(d*i - f*g)
        val C = (d*h - e*g)
        val D = -(b*i - c*h)
        val E = (a*i - c*g)
        val F = -(a*h - b*g)
        val G = (b*f - c*e)
        val H = -(a*f - c*d)
        val I = (a*e - b*d)
        val det = a*A + b*B + c*C
        if (det == 0f) return
        val invDet = 1f / det
        out[0] = A*invDet; out[1] = D*invDet; out[2] = G*invDet
        out[3] = B*invDet; out[4] = E*invDet; out[5] = H*invDet
        out[6] = C*invDet; out[7] = F*invDet; out[8] = I*invDet
    }

    private fun pitchRollFromMatrix(r: FloatArray): Pair<Double, Double> {
        val pitch = asin((-r[7]).toDouble()) * (180.0 / Math.PI)
        val roll = atan2(r[6].toDouble(), r[8].toDouble()) * (180.0 / Math.PI)
        return pitch to roll
    }
}
