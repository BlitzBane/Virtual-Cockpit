package com.adityaapte.virtualcockpit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import android.graphics.Color
import android.view.WindowManager
import androidx.core.view.WindowInsetsControllerCompat



class MainActivity : ComponentActivity() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager

    // ---- GPS
    private var locationCallback: LocationCallback? = null

    // ---- GNSS
    private var gnssCallback: GnssStatus.Callback? = null

    // ---- Sensors
    private var gameRotListener: SensorEventListener? = null
    private var gravityListener: SensorEventListener? = null

    private var gyroListener: SensorEventListener? = null
    private var accelListener: SensorEventListener? = null
    private var linAccListener: SensorEventListener? = null
    private var magListener: SensorEventListener? = null
    private var pressureListener: SensorEventListener? = null

    private val _nav = MutableStateFlow(NavData())
    val nav: StateFlow<NavData> = _nav

    private var demoJob: Job? = null

    // attitude calibration (inverse matrix in remapped frame)
    private var calibInv: FloatArray? = null

    @Volatile private var mountModeVolatile: MountMode = MountMode.AUTO
    @Volatile private var mountStyleVolatile: MountStyle = MountStyle.FLAT

    // ---- Baro state
    private var seaLevelHpa: Double = 1013.25          // will self-calibrate if GPS alt available
    private var lastBaroAltM: Double? = null
    private var lastBaroTms: Long? = null
    private var vsiFpmSmoothed: Double = 0.0

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startAll()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        MapLibre.getInstance(
            applicationContext,
            "unused",
            WellKnownTileServer.MapLibre
        )

        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        setContent {
            val navState by nav.collectAsState(initial = NavData())

            MaterialTheme {
                Surface(color = androidx.compose.ui.graphics.Color.Black) {
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
                        onSetMountMode = { setMountMode(it) },
                        isDemo = navState.isDemo   // ✅ use collected state
                    )
                }
            }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

        WindowCompat.setDecorFitsSystemWindows(window, true)

        window.statusBarColor = android.graphics.Color.BLACK

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false   // white icons
        }

        window.isStatusBarContrastEnforced = false
    }

    override fun onResume() {
        super.onResume()
        // Keep screen awake while app is visible
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        startAll()
    }

    override fun onPause() {
        super.onPause()
        // Allow screen to sleep once app is not visible
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopAll()
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun startAll() {
        if (!hasFineLocationPermission()) return
        if (nav.value.isDemo) return

        startGravityAutoDetect()
        startGameRotationAttitude()

        startGps()
        startGnss()
        startImuStreams()
        startBarometer()
    }

    private fun stopAll() {
        stopGps()
        stopGnss()
        stopImuStreams()
        stopBarometer()

        stopGameRotationAttitude()
        stopGravityAutoDetect()
    }

    // ---------- Mount mode handling ----------
    private fun setMountMode(mode: MountMode) {
        mountModeVolatile = mode

        val forcedStyle = when (mode) {
            MountMode.FLAT -> MountStyle.FLAT
            MountMode.UPRIGHT -> MountStyle.UPRIGHT
            MountMode.AUTO -> mountStyleVolatile
        }

        mountStyleVolatile = forcedStyle
        calibInv = null

        _nav.update { it.copy(mountMode = mode, mountStyle = mountStyleVolatile) }
    }

    private fun startGravityAutoDetect() {
        if (gravityListener != null) return
        val g = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: return

        gravityListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (mountModeVolatile != MountMode.AUTO) return

                val gy = event.values[1].toDouble()
                val gz = event.values[2].toDouble()

                val newStyle = if (abs(gz) > abs(gy)) MountStyle.FLAT else MountStyle.UPRIGHT

                if (newStyle != mountStyleVolatile) {
                    mountStyleVolatile = newStyle
                    calibInv = null
                    _nav.update { it.copy(mountMode = MountMode.AUTO, mountStyle = mountStyleVolatile) }
                }

                // also publish raw gravity vector
                _nav.update {
                    it.copy(
                        gravityX = event.values[0].toDouble(),
                        gravityY = event.values[1].toDouble(),
                        gravityZ = event.values[2].toDouble()
                    )
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(gravityListener, g, SensorManager.SENSOR_DELAY_UI)
    }

    private fun stopGravityAutoDetect() {
        gravityListener?.let { sensorManager.unregisterListener(it) }
        gravityListener = null
    }

    // ---------- Attitude ----------
    private fun startGameRotationAttitude() {
        if (gameRotListener != null) return
        val rot = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: return

        gameRotListener = object : SensorEventListener {
            private val rotMat = FloatArray(9)
            private val mapped = FloatArray(9)
            private val rel = FloatArray(9)

            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotMat, event.values)

                remapForMount(rotMat, mapped, mountStyleVolatile)

                val calib = calibInv
                val use = if (calib == null) mapped else multiply3x3(calib, mapped, rel)

                val (pitch, roll) = pitchRollFromMatrix(use, mountStyleVolatile)
                _nav.update { it.copy(pitchDeg = pitch, rollDeg = roll, mountStyle = mountStyleVolatile) }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(gameRotListener, rot, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopGameRotationAttitude() {
        gameRotListener?.let { sensorManager.unregisterListener(it) }
        gameRotListener = null
    }

    private fun calibrateAttitudeNow() {
        val rot = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: return

        val oneShot = object : SensorEventListener {
            private val m = FloatArray(9)
            private val mapped = FloatArray(9)
            private val inv = FloatArray(9)

            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(m, event.values)
                remapForMount(m, mapped, mountStyleVolatile)
                invert3x3(mapped, inv)
                calibInv = inv
                sensorManager.unregisterListener(this)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(oneShot, rot, SensorManager.SENSOR_DELAY_FASTEST)
    }

    /**
     * Device axes (Android):
     *  X = right, Y = up, Z = out of screen
     *
     * We remap into an “aircraft body” frame:
     *  X = forward, Y = right, Z = down
     */
    private fun remapForMount(src: FloatArray, dst: FloatArray, style: MountStyle) {
        when (style) {
            MountStyle.FLAT -> {
                SensorManager.remapCoordinateSystem(
                    src,
                    SensorManager.AXIS_MINUS_Y,
                    SensorManager.AXIS_MINUS_X,
                    dst
                )
            }
            MountStyle.UPRIGHT -> {
                SensorManager.remapCoordinateSystem(
                    src,
                    SensorManager.AXIS_MINUS_Y,
                    SensorManager.AXIS_Z,
                    dst
                )
            }
        }
    }

    private fun pitchRollFromMatrix(r: FloatArray, style: MountStyle): Pair<Double, Double> {
        val o = FloatArray(3)
        SensorManager.getOrientation(r, o)

        val rawRoll = Math.toDegrees(o[1].toDouble())
        val rawPitch = Math.toDegrees(o[2].toDouble())

        val pitch = -rawPitch
        val roll = if (style == MountStyle.UPRIGHT) -rawRoll else rawRoll

        return pitch to roll
    }

    // ---------- GPS ----------
    @android.annotation.SuppressLint("MissingPermission")
    private fun startGps() {
        if (locationCallback != null) return

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            200L // ms
        ).apply {
            setMinUpdateIntervalMillis(100L)
            setMaxUpdateDelayMillis(500L)
            setWaitForAccurateLocation(false)
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return

                val fixTimeMs = if (Build.VERSION.SDK_INT >= 17) {
                    // monotonic time since boot for "freshness"
                    loc.elapsedRealtimeNanos / 1_000_000L
                } else {
                    SystemClock.elapsedRealtime()
                }

                val lat = loc.latitude
                val lon = loc.longitude

                val speed = if (loc.hasSpeed()) loc.speed.toDouble() else null
                val track = if (loc.hasBearing()) loc.bearing.toDouble() else null
                val alt = if (loc.hasAltitude()) loc.altitude else null
                val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null

                val vAcc = if (Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy()) {
                    loc.verticalAccuracyMeters.toDouble()
                } else null

                val sAcc = if (Build.VERSION.SDK_INT >= 26 && loc.hasSpeedAccuracy()) {
                    loc.speedAccuracyMetersPerSecond.toDouble()
                } else null

                val bAcc = if (Build.VERSION.SDK_INT >= 26 && loc.hasBearingAccuracy()) {
                    loc.bearingAccuracyDegrees.toDouble()
                } else null

                // Optional: refine sea-level pressure estimate using GPS altitude when available
                if (alt != null) {
                    val p = nav.value.pressureHpa
                    if (p != null) {
                        // derive sea-level pressure so baro altitude matches GPS altitude (slow blend)
                        val est = pressureToSeaLevel(p, alt)
                        seaLevelHpa = seaLevelHpa * 0.98 + est * 0.02
                    }
                }

                _nav.update {
                    it.copy(
                        lat = lat,
                        lon = lon,
                        speedMps = speed,
                        trackDeg = track,
                        gpsAltitudeM = alt,
                        accuracyM = acc,
                        verticalAccuracyM = vAcc,
                        speedAccuracyMps = sAcc,
                        bearingAccuracyDeg = bAcc,
                        provider = loc.provider,
                        fixTimeMs = fixTimeMs
                    )
                }
            }
        }

        try {
            fusedClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
        } catch (_: SecurityException) {
            // Permission revoked mid-flight (or OEM weirdness)
            stopGps()
        }

    }

    private fun stopGps() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null
    }

    // ---------- GNSS Satellites ----------
    @android.annotation.SuppressLint("MissingPermission")
    private fun startGnss() {
        if (gnssCallback != null) return
        if (Build.VERSION.SDK_INT < 24) return
        if (!hasFineLocationPermission()) return

        gnssCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var inView = 0
                var used = 0
                val sats = ArrayList<GnssSat>(status.satelliteCount)

                for (i in 0 until status.satelliteCount) {
                    inView++

                    val usedFix = status.usedInFix(i)
                    if (usedFix) used++

                    sats.add(
                        GnssSat(
                            svid = status.getSvid(i),
                            constellation = status.getConstellationType(i),
                            azimuthDeg = status.getAzimuthDegrees(i),
                            elevationDeg = status.getElevationDegrees(i),
                            cn0DbHz = status.getCn0DbHz(i),
                            usedInFix = usedFix
                        )
                    )
                }

                _nav.update { it.copy(satsInView = inView, satsUsed = used, satellites = sats) }
            }

        }

        try {
            if (Build.VERSION.SDK_INT >= 30) {
                locationManager.registerGnssStatusCallback(
                    ContextCompat.getMainExecutor(this),
                    gnssCallback!!
                )
            } else {
                @Suppress("DEPRECATION")
                locationManager.registerGnssStatusCallback(
                    gnssCallback!!,
                    Handler(Looper.getMainLooper())
                )
            }
        } catch (_: SecurityException) {
            // permission missing
        }
    }

    private fun stopGnss() {
        if (Build.VERSION.SDK_INT < 24) return
        try {
            gnssCallback?.let { locationManager.unregisterGnssStatusCallback(it) }
        } catch (_: Exception) {
        }
        gnssCallback = null
    }

    // ---------- IMU raw streams + turn rate ----------
    private fun startImuStreams() {
        // Gyro
        if (gyroListener == null) {
            val s = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            if (s != null) {
                gyroListener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        val gx = event.values[0].toDouble() // rad/s
                        val gy = event.values[1].toDouble()
                        val gz = event.values[2].toDouble()

                        // Map into aircraft-body Z rate for turn rate (deg/sec)
                        val bodyZ = bodyYawRateRadPerSec(gx, gy, gz, mountStyleVolatile)
                        val turnRateDps = Math.toDegrees(bodyZ)

                        _nav.update {
                            it.copy(
                                gyroX = gx,
                                gyroY = gy,
                                gyroZ = gz,
                                turnRateDps = turnRateDps
                            )
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sensorManager.registerListener(gyroListener, s, SensorManager.SENSOR_DELAY_GAME)
            }
        }

        // Accelerometer
        if (accelListener == null) {
            val s = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (s != null) {
                accelListener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        _nav.update {
                            it.copy(
                                accelX = event.values[0].toDouble(),
                                accelY = event.values[1].toDouble(),
                                accelZ = event.values[2].toDouble()
                            )
                        }
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sensorManager.registerListener(accelListener, s, SensorManager.SENSOR_DELAY_UI)
            }
        }

        // Linear acceleration
        if (linAccListener == null) {
            val s = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            if (s != null) {
                linAccListener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        _nav.update {
                            it.copy(
                                linAccX = event.values[0].toDouble(),
                                linAccY = event.values[1].toDouble(),
                                linAccZ = event.values[2].toDouble()
                            )
                        }
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sensorManager.registerListener(linAccListener, s, SensorManager.SENSOR_DELAY_UI)
            }
        }

        // Magnetometer
        if (magListener == null) {
            val s = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            if (s != null) {
                magListener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        _nav.update {
                            it.copy(
                                magX = event.values[0].toDouble(),
                                magY = event.values[1].toDouble(),
                                magZ = event.values[2].toDouble()
                            )
                        }
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sensorManager.registerListener(magListener, s, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    private fun stopImuStreams() {
        gyroListener?.let { sensorManager.unregisterListener(it) }
        gyroListener = null

        accelListener?.let { sensorManager.unregisterListener(it) }
        accelListener = null

        linAccListener?.let { sensorManager.unregisterListener(it) }
        linAccListener = null

        magListener?.let { sensorManager.unregisterListener(it) }
        magListener = null
    }

    /**
     * Convert device gyro vector into aircraft-body yaw (turn) rate about body Z (down).
     * From your remaps:
     *  - FLAT: bodyZ ~= deviceZ
     *  - UPRIGHT: bodyZ ~= deviceX
     */
    private fun bodyYawRateRadPerSec(gx: Double, gy: Double, gz: Double, style: MountStyle): Double {
        return when (style) {
            MountStyle.FLAT -> gz
            MountStyle.UPRIGHT -> gx
        }
    }

    // ---------- Barometer -> baro altitude + VSI ----------
    private fun startBarometer() {
        if (pressureListener != null) return
        val p = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return

        pressureListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val pressureHpa = event.values[0].toDouble()
                val baroAltM = pressureToAltitudeM(pressureHpa, seaLevelHpa)

                val now = SystemClock.elapsedRealtime()
                val lastAlt = lastBaroAltM
                val lastT = lastBaroTms

                var vsiFpm: Double? = null
                if (lastAlt != null && lastT != null) {
                    val dt = (now - lastT).coerceAtLeast(1L) / 1000.0
                    val climbMps = (baroAltM - lastAlt) / dt
                    vsiFpm = climbMps * 196.850394 // m/s -> ft/min

                    // smooth VSI to reduce noise
                    vsiFpmSmoothed = vsiFpmSmoothed * 0.85 + vsiFpm * 0.15
                }

                lastBaroAltM = baroAltM
                lastBaroTms = now

                _nav.update {
                    it.copy(
                        pressureHpa = pressureHpa,
                        baroAltitudeM = baroAltM,
                        vsiFpm = vsiFpm?.let { vsiFpmSmoothed }
                    )
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(pressureListener, p, SensorManager.SENSOR_DELAY_UI)
    }

    private fun stopBarometer() {
        pressureListener?.let { sensorManager.unregisterListener(it) }
        pressureListener = null
        lastBaroAltM = null
        lastBaroTms = null
        vsiFpmSmoothed = 0.0
    }

    private fun pressureToAltitudeM(pressureHpa: Double, seaLevelHpa: Double): Double {
        // International Standard Atmosphere (approx)
        return 44330.0 * (1.0 - (pressureHpa / seaLevelHpa).pow(0.190294957))
    }

    private fun pressureToSeaLevel(pressureHpa: Double, altitudeM: Double): Double {
        // invert ISA formula: p0 = p / (1 - h/44330)^(1/0.1903)
        val term = (1.0 - altitudeM / 44330.0).coerceIn(0.1, 1.0)
        return pressureHpa / term.pow(1.0 / 0.190294957)
    }

    // ---------- 3x3 helpers ----------
    private fun multiply3x3(a: FloatArray, b: FloatArray, out: FloatArray): FloatArray {
        out[0] = a[0] * b[0] + a[1] * b[3] + a[2] * b[6]
        out[1] = a[0] * b[1] + a[1] * b[4] + a[2] * b[7]
        out[2] = a[0] * b[2] + a[1] * b[5] + a[2] * b[8]

        out[3] = a[3] * b[0] + a[4] * b[3] + a[5] * b[6]
        out[4] = a[3] * b[1] + a[4] * b[4] + a[5] * b[7]
        out[5] = a[3] * b[2] + a[4] * b[5] + a[5] * b[8]

        out[6] = a[6] * b[0] + a[7] * b[3] + a[8] * b[6]
        out[7] = a[6] * b[1] + a[7] * b[4] + a[8] * b[7]
        out[8] = a[6] * b[2] + a[7] * b[5] + a[8] * b[8]
        return out
    }

    private fun invert3x3(m: FloatArray, out: FloatArray) {
        val det =
            m[0] * (m[4] * m[8] - m[5] * m[7]) -
                    m[1] * (m[3] * m[8] - m[5] * m[6]) +
                    m[2] * (m[3] * m[7] - m[4] * m[6])

        if (det == 0f) {
            for (i in 0..8) out[i] = if (i % 4 == 0) 1f else 0f
            return
        }

        val invDet = 1f / det
        out[0] = (m[4] * m[8] - m[5] * m[7]) * invDet
        out[1] = (m[2] * m[7] - m[1] * m[8]) * invDet
        out[2] = (m[1] * m[5] - m[2] * m[4]) * invDet

        out[3] = (m[5] * m[6] - m[3] * m[8]) * invDet
        out[4] = (m[0] * m[8] - m[2] * m[6]) * invDet
        out[5] = (m[2] * m[3] - m[0] * m[5]) * invDet

        out[6] = (m[3] * m[7] - m[4] * m[6]) * invDet
        out[7] = (m[1] * m[6] - m[0] * m[7]) * invDet
        out[8] = (m[0] * m[4] - m[1] * m[3]) * invDet
    }

    // ---------- Demo (now includes GPS + track + speed so MAP works) ----------
    private fun startDemo() {
        stopAll()
        demoJob?.cancel()

        // Choose a center point (India) so your airports overlay looks alive
        val centerLat = 13.0827   // Chennai-ish
        val centerLon = 80.2707

        demoJob = CoroutineScope(Dispatchers.Main).launch {
            val start = SystemClock.elapsedRealtime()
            while (isActive) {
                val t = (SystemClock.elapsedRealtime() - start) / 1000.0

                // small circle track
                val r = 0.08 // degrees ~ 9km
                val lat = centerLat + r * cos(t * 0.06)
                val lon = centerLon + r * sin(t * 0.06)

                // compute bearing along the path
                val dLat = -r * sin(t * 0.06) * 0.06
                val dLon =  r * cos(t * 0.06) * 0.06
                val bearingRad = atan2(dLon, dLat)
                val bearingDeg = (Math.toDegrees(bearingRad) + 360.0) % 360.0

                _nav.update {
                    it.copy(
                        isDemo = true,
                        // attitude demo
                        pitchDeg = 5.0 * sin(t),
                        rollDeg = 20.0 * sin(t * 0.7),

                        // GPS demo
                        lat = lat,
                        lon = lon,
                        trackDeg = bearingDeg,
                        speedMps = 55.0,           // ~107 kt
                        gpsAltitudeM = 600.0,
                        accuracyM = 5.0,
                        provider = "demo",
                        fixTimeMs = SystemClock.elapsedRealtime(),

                        // GNSS demo
                        satsInView = 18,
                        satsUsed = 12
                    )
                }
                delay(33)
            }
        }
    }

    private fun stopDemo() {
        demoJob?.cancel()
        demoJob = null
        _nav.update { it.copy(isDemo = false) }
        startAll()
    }
}
