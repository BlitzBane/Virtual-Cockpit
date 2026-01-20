package com.adityaapte.virtualcockpit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CockpitScreen(nav: NavData) {
    val speedKts = nav.speedMps?.let { it * 1.943844 }
    val gpsAltFt = nav.gpsAltitudeM?.let { it * 3.28084 }
    val vsi = nav.vsiFpm
    val hdg = nav.headingDeg

    // 2 columns x 3 rows = classic 6-pack layout
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Virtual Cockpit", style = MaterialTheme.typography.titleLarge)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InstrumentCard(
                modifier = Modifier.weight(1f),
                title = "Airspeed"
            ) {
                SimpleDial(value = speedKts, unit = "kt", min = 0.0, max = 600.0)
            }
            InstrumentCard(
                modifier = Modifier.weight(1f),
                title = "Attitude"
            ) {
                AttitudeIndicator(pitchDeg = nav.pitchDeg, rollDeg = nav.rollDeg)
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InstrumentCard(
                modifier = Modifier.weight(1f),
                title = "Altimeter"
            ) {
                SimpleDial(value = gpsAltFt, unit = "ft", min = 0.0, max = 45000.0)
            }
            InstrumentCard(
                modifier = Modifier.weight(1f),
                title = "Turn"
            ) {
                PlaceholderInstrument("Turn rate\n(Next)")
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InstrumentCard(
                modifier = Modifier.weight(1f),
                title = "VSI"
            ) {
                SimpleDial(value = vsi, unit = "fpm", min = -3000.0, max = 3000.0)
            }
            InstrumentCard(
                modifier = Modifier.weight(1f),
                title = "Heading"
            ) {
                SimpleDial(value = hdg, unit = "°", min = 0.0, max = 360.0, wrap360 = true)
            }
        }
    }
}

@Composable
private fun InstrumentCard(
    modifier: Modifier = Modifier,
    title: String,
    content: @Composable BoxScope.() -> Unit
) {
    Card(
        modifier = modifier.aspectRatio(1f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
                content = content
            )
        }
    }
}

@Composable
private fun PlaceholderInstrument(text: String) {
    Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun AttitudeIndicator(pitchDeg: Double?, rollDeg: Double?) {
    val pitch = pitchDeg ?: 0.0
    val roll = rollDeg ?: 0.0

    val skyColor = Color(0xFF3399FF)
    val groundColor = Color(0xFF996633)
    val lineColor = Color.White

    Canvas(modifier = Modifier.fillMaxSize()) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val radius = size.width / 2f

        // Rotate the entire canvas to simulate roll
        rotate(degrees = roll.toFloat(), pivot = Offset(centerX, centerY)) {
            // Background (sky and ground)
            clipRect {
                // Translate vertically for pitch
                val pitchOffset = size.height * (pitch / 90.0) // 90 deg = full screen
                translate(top = pitchOffset.toFloat()) {
                    drawRect(color = skyColor, size = size.copy(height = size.height / 2f))
                    drawRect(
                        color = groundColor,
                        topLeft = Offset(0f, size.height / 2f),
                        size = size.copy(height = size.height / 2f)
                    )
                }
            }

            // Horizon line
            drawLine(
                color = lineColor,
                start = Offset(-size.width, centerY),
                end = Offset(size.width * 2, centerY),
                strokeWidth = 3f
            )
        }

        // Fixed aircraft symbol (doesn't roll)
        val aircraftSymbolWidth = radius * 0.6f
        val aircraftSymbolHeight = radius * 0.4f
        drawCircle(color = lineColor, radius = 8f, center = Offset(centerX, centerY))
        drawLine(
            color = lineColor,
            start = Offset(centerX - aircraftSymbolWidth / 2, centerY),
            end = Offset(centerX + aircraftSymbolWidth / 2, centerY),
            strokeWidth = 5f
        )
        drawLine(
            color = lineColor,
            start = Offset(centerX, centerY),
            end = Offset(centerX, centerY + aircraftSymbolHeight),
            strokeWidth = 5f
        )
    }
}

/**
 * Simple dial gauge: circular arc + needle + numeric readout.
 * (We’ll replace each with a proper aviation-style instrument later.)
 */
@Composable
private fun SimpleDial(
    value: Double?,
    unit: String,
    min: Double,
    max: Double,
    wrap360: Boolean = false
) {
    val v = value ?: Double.NaN

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val ink = MaterialTheme.colorScheme.onSurface
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = (minOf(size.width, size.height) * 0.35f)

            // outer circle
            drawCircle(
                color = ink,
                style = Stroke(width = 6f),
                radius = radius,
                center = center
            )


            if (!v.isNaN()) {
                val clamped = when {
                    wrap360 -> ((v % 360) + 360) % 360
                    else -> v.coerceIn(min, max)
                }

                val t = if (wrap360) clamped / 360.0 else (clamped - min) / (max - min)
                // Map 0..1 to -135..+135 degrees (270° sweep)
                val angleDeg = (-135.0 + 270.0 * t)
                val angleRad = Math.toRadians(angleDeg)

                val needleEnd = Offset(
                    (center.x + (radius * 0.85f * cos(angleRad))).toFloat(),
                    (center.y + (radius * 0.85f * sin(angleRad))).toFloat()
                )

                drawLine(
                    color = ink,
                    start = center,
                    end = needleEnd,
                    strokeWidth = 6f
                )


                drawCircle(
                    color = ink,
                    radius = 10f,
                    center = center
                )

            }
        }

        val display = if (v.isNaN()) "—" else {
            when (unit) {
                "°" -> "${v.toInt()}°"
                "kt" -> "${v.toInt()} kt"
                "fpm" -> "${v.toInt()} fpm"
                else -> v.toString()
            }
        }
        Text(display, style = MaterialTheme.typography.bodyLarge)
    }
}
