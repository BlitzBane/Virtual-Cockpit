package com.adityaapte.virtualcockpit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.sqrt



@Composable
fun RawDataScreen(
    nav: NavData,
    isDemo: Boolean,
    onToggleDemo: (Boolean) -> Unit,
    onCalibrateAttitude: () -> Unit,
    onSetMountMode: (MountMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    var selectedSat by remember { mutableStateOf<GnssSat?>(null) }


    Column(
        modifier = modifier
            .background(Color(0xFF101418))
            .padding(12.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // CAL button (top-right)
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterEnd
        ) {
            Chip("CALIBRATE") { onCalibrateAttitude() }
        }

        SectionTitle("MOUNT")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SelectChip("AUTO", nav.mountMode == MountMode.AUTO) { onSetMountMode(MountMode.AUTO) }
            SelectChip("FLAT", nav.mountMode == MountMode.FLAT) { onSetMountMode(MountMode.FLAT) }
            SelectChip("UPRIGHT", nav.mountMode == MountMode.UPRIGHT) { onSetMountMode(MountMode.UPRIGHT) }
        }
        ValueRow("Active Style", nav.mountStyle.name)

        SectionTitle("GPS / NAV")
        ValueRow("Satellites (Used/In View)", "${nav.satsUsed ?: "—"} / ${nav.satsInView ?: "—"}")
        ValueRow("Satellites In View", nav.satsInView?.toString() ?: "—")
        SectionTitle("SATELLITES")
        SatelliteSkyPlot(
            sats = nav.satellites,
            onSelect = { selectedSat = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
        )

        val s = selectedSat
        if (s == null) {
            ValueRow("Tap a satellite", "to see details")
        } else {
            ValueRow("SVID", s.svid.toString())
            ValueRow("Az / El", "${fmt(s.azimuthDeg.toDouble())}° / ${fmt(s.elevationDeg.toDouble())}°")
            ValueRow("CN0", "${fmt(s.cn0DbHz.toDouble())} dB-Hz")
            ValueRow("Used In Fix", if (s.usedInFix) "YES" else "NO")
            ValueRow("Constellation", s.constellation.toString())
        }
        ValueRow("Lat", nav.lat?.toString() ?: "—")
        ValueRow("Lon", nav.lon?.toString() ?: "—")
        SectionTitle("Telemetry")
        ValueRow("Speed", nav.speedMps?.let { "${(it * 1.943844).roundToInt()} kt (${fmt(it)} m/s)" } ?: "—")
        ValueRow("Track", nav.trackDeg?.let { "${fmt(it)}°" } ?: "—")
        ValueRow("GPS Alt", nav.gpsAltitudeM?.let { "${fmt(it)} m / ${fmt(it * 3.28084)} ft" } ?: "—")
        ValueRow("Baro Alt", nav.baroAltitudeM?.let { "${fmt(it)} m / ${fmt(it * 3.28084)} ft" } ?: "—")
        ValueRow("VSI", nav.vsiFpm?.let { "${fmt(it)} ft/min" } ?: "—")
        ValueRow("Pressure", nav.pressureHpa?.let { "${fmt(it)} hPa" } ?: "—")


        SectionTitle("ATTITUDE")
        ValueRow("Pitch", nav.pitchDeg?.let { "${fmt(it)}°" } ?: "—")
        ValueRow("Roll", nav.rollDeg?.let { "${fmt(it)}°" } ?: "—")
        ValueRow("Turn Rate", nav.turnRateDps?.let { "${fmt(it)} °/s" } ?: "—")
    }
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

@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Color(0xFF00E676) else Color(0xAA000000)
    val fg = if (selected) Color.Black else Color(0xFF00E676)

    Box(
        modifier = Modifier
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable { onClick() }
    ) {
        androidx.compose.material3.Text(label, color = fg)
    }
}

@Composable
private fun SatelliteSkyPlot(
    sats: List<GnssSat>,
    onSelect: (GnssSat?) -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = Color(0xFF0E1A22)
    val ring = Color(0xFF2A3A45)
    val textColor = android.graphics.Color.argb(220, 220, 220, 220)

    Canvas(modifier = modifier
        .background(bg)
        .pointerInput(sats) {
            detectTapGestures { tap ->
                val cx = size.width / 2f
                val cy = size.height / 2f
                val r = min(size.width.toFloat(), size.height.toFloat()) * 0.42f

                val hitRadius = 22f // dot (16) + tolerance
                var best: GnssSat? = null
                var bestD2 = Float.MAX_VALUE

                for (s in sats) {
                    val elev = s.elevationDeg.coerceIn(0f, 90f)
                    val satR = ((90f - elev) / 90f) * r
                    val ang = Math.toRadians((s.azimuthDeg - 90f).toDouble())
                    val x = cx + (cos(ang) * satR).toFloat()
                    val y = cy + (sin(ang) * satR).toFloat()

                    val dx = tap.x - x
                    val dy = tap.y - y
                    val d2 = dx * dx + dy * dy
                    if (d2 <= hitRadius * hitRadius && d2 < bestD2) {
                        bestD2 = d2
                        best = s
                    }
                }
                onSelect(best) // null clears selection
            }
        }
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = min(size.width, size.height) * 0.42f

        // Rings (outer + inner)
        drawCircle(color = ring, radius = r, center = Offset(cx, cy), style = Stroke(width = 2f))
        drawCircle(color = ring, radius = r * 0.66f, center = Offset(cx, cy), style = Stroke(width = 1.5f))
        drawCircle(color = ring, radius = r * 0.33f, center = Offset(cx, cy), style = Stroke(width = 1.2f))

        // Cross lines
        drawLine(ring, Offset(cx - r, cy), Offset(cx + r, cy), strokeWidth = 1.2f)
        drawLine(ring, Offset(cx, cy - r), Offset(cx, cy + r), strokeWidth = 1.2f)

        // Cardinal labels
        drawContext.canvas.nativeCanvas.apply {
            val p = android.graphics.Paint().apply {
                isAntiAlias = true
                color = textColor
                textSize = 12.sp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
            }
            drawText("N", cx, cy - r - 10f, p)
            drawText("S", cx, cy + r + 20f, p)
            drawText("W", cx - r - 14f, cy + 4f, p)
            drawText("E", cx + r + 14f, cy + 4f, p)
        }

        // Satellites
        sats.forEach { s ->
            val elev = s.elevationDeg.coerceIn(0f, 90f)
            val az = s.azimuthDeg

            // radius: 90° elev => center, 0° elev => outer ring
            val satR = ((90f - elev) / 90f) * r

            // azimuth: 0° = North (top). Canvas angle 0° = East, so shift by -90
            val ang = Math.toRadians((az - 90f).toDouble())
            val x = cx + (cos(ang) * satR).toFloat()
            val y = cy + (sin(ang) * satR).toFloat()

            val c = when {
                s.usedInFix -> Color(0xFF00E676)
                s.cn0DbHz >= 30f -> Color(0xFFFFEB3B)
                s.cn0DbHz >= 20f -> Color(0xFFFF9800)
                else -> Color(0xFFFF3D00)
            }

            // circle dot
            drawCircle(
                color = c,
                radius = 16f,
                center = Offset(x, y)
            )

            // label (SVID)
            drawContext.canvas.nativeCanvas.apply {
                val p = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = android.graphics.Color.BLACK
                    textSize = 12.sp.toPx()
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                drawText(s.svid.toString(), x, y + 5f, p)
            }

            // tiny outline for clarity
            drawCircle(
                color = Color.Black.copy(alpha = 0.35f),
                radius = 16f,
                center = Offset(x, y),
                style = Stroke(width = 2f, cap = StrokeCap.Round)
            )
        }
    }
}

