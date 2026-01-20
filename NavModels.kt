package com.adityaapte.virtualcockpit

data class NavData(
    // ---- GPS ----
    val lat: Double? = null,
    val lon: Double? = null,
    val speedMps: Double? = null,
    val trackDeg: Double? = null,           // GPS course/track
    val gpsAltitudeM: Double? = null,
    val accuracyM: Double? = null,          // horizontal accuracy

    val verticalAccuracyM: Double? = null,
    val speedAccuracyMps: Double? = null,
    val bearingAccuracyDeg: Double? = null,
    val provider: String? = null,
    val fixTimeMs: Long? = null,

    // ---- GNSS ----
    val satsInView: Int? = null,
    val satsUsed: Int? = null,

    // ---- Barometer ----
    val pressureHpa: Double? = null,
    val baroAltitudeM: Double? = null,
    val vsiFpm: Double? = null,

    // ---- Attitude ----
    val pitchDeg: Double? = null,
    val rollDeg: Double? = null,

    // ---- Turn rate ----
    val turnRateDps: Double? = null,        // deg/sec (approx from gyro Z)

    // ---- Raw IMU vectors (nerd mode) ----
    val gyroX: Double? = null,
    val gyroY: Double? = null,
    val gyroZ: Double? = null,

    val accelX: Double? = null,
    val accelY: Double? = null,
    val accelZ: Double? = null,

    val gravityX: Double? = null,
    val gravityY: Double? = null,
    val gravityZ: Double? = null,

    val linAccX: Double? = null,
    val linAccY: Double? = null,
    val linAccZ: Double? = null,

    val magX: Double? = null,
    val magY: Double? = null,
    val magZ: Double? = null,

    // ---- Demo mode marker ----
    val isDemo: Boolean = false
)
