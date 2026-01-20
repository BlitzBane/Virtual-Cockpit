package com.adityaapte.virtualcockpit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun RawDataScreen(
    nav: NavData,
    isDemo: Boolean,
    onToggleDemo: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()

    Column(
        modifier = modifier
            .background(Color(0xFF101418))
            .padding(12.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionTitle("MODE")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ValueRow("Demo Mode", if (isDemo) "ON" else "OFF")
            Chip(if (isDemo) "STOP" else "START") { onToggleDemo(!isDemo) }
        }

        SectionTitle("GPS / NAV")
        ValueRow("Lat", nav.lat?.toString() ?: "—")
        ValueRow("Lon", nav.lon?.toString() ?: "—")
        ValueRow("Speed", nav.speedMps?.let { "${(it * 1.943844).roundToInt()} kt (${fmt(it)} m/s)" } ?: "—")
        ValueRow("Track", nav.trackDeg?.let { "${fmt(it)}°" } ?: "—")
        // NOTE: headingDeg was referenced earlier but does not exist in NavData.
        // If you want magnetic heading, we can add it properly later.

        ValueRow("GPS Alt", nav.gpsAltitudeM?.let { "${fmt(it)} m / ${fmt(it * 3.28084)} ft" } ?: "—")
        ValueRow("Baro Alt", nav.baroAltitudeM?.let { "${fmt(it)} m / ${fmt(it * 3.28084)} ft" } ?: "—")
        ValueRow("VSI", nav.vsiFpm?.let { "${fmt(it)} ft/min" } ?: "—")

        SectionTitle("ACCURACY")
        ValueRow("H Acc", nav.accuracyM?.let { "${fmt(it)} m" } ?: "—")
        ValueRow("V Acc", nav.verticalAccuracyM?.let { "${fmt(it)} m" } ?: "—")
        ValueRow("Speed Acc", nav.speedAccuracyMps?.let { "${fmt(it)} m/s" } ?: "—")
        ValueRow("Bearing Acc", nav.bearingAccuracyDeg?.let { "${fmt(it)}°" } ?: "—")

        SectionTitle("SATELLITES")
        ValueRow("Sats In View", nav.satsInView?.toString() ?: "—")
        ValueRow("Sats Used", nav.satsUsed?.toString() ?: "—")

        SectionTitle("ATTITUDE")
        ValueRow("Pitch", nav.pitchDeg?.let { "${fmt(it)}°" } ?: "—")
        ValueRow("Roll", nav.rollDeg?.let { "${fmt(it)}°" } ?: "—")
        ValueRow("Turn Rate", nav.turnRateDps?.let { "${fmt(it)} °/s" } ?: "—")

        SectionTitle("PRESSURE")
        ValueRow("Pressure", nav.pressureHpa?.let { "${fmt(it)} hPa" } ?: "—")

        SectionTitle("SENSORS (RAW)")
        ValueRow("Gyro X/Y/Z", triple(nav.gyroX, nav.gyroY, nav.gyroZ))
        ValueRow("Accel X/Y/Z", triple(nav.accelX, nav.accelY, nav.accelZ))
        ValueRow("Gravity X/Y/Z", triple(nav.gravityX, nav.gravityY, nav.gravityZ))
        ValueRow("LinAcc X/Y/Z", triple(nav.linAccX, nav.linAccY, nav.linAccZ))
        ValueRow("Mag X/Y/Z", triple(nav.magX, nav.magY, nav.magZ))

        SectionTitle("META")
        ValueRow("Provider", nav.provider ?: "—")
        ValueRow("Fix Time", nav.fixTimeMs?.toString() ?: "—")
    }
}

private fun triple(a: Double?, b: Double?, c: Double?): String {
    if (a == null || b == null || c == null) return "—"
    return "${fmt(a)}, ${fmt(b)}, ${fmt(c)}"
}

private fun fmt(v: Double): String = "%.3f".format(v)

@Composable
private fun SectionTitle(text: String) {
    androidx.compose.material3.Text(
        text = text,
        color = Color(0xFF00E676),
        modifier = Modifier.padding(top = 10.dp)
    )
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        androidx.compose.material3.Text(label, color = Color(0xFFE0E0E0))
        androidx.compose.material3.Text(value, color = Color.White)
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(Color(0xAA000000))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable { onClick() }
    ) {
        androidx.compose.material3.Text(label, color = Color(0xFF00E676))
    }
}
