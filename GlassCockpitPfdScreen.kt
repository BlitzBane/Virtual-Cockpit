package com.adityaapte.virtualcockpit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.*





@Composable
fun GlassCockpitPfdScreen(
    nav: NavData,
    modifier: Modifier = Modifier,
    onCalibrate: () -> Unit
) {
    // G1000-ish palette
    val bg = Color.Black
    val sky = Color(0xFF0B3FA6)
    val ground = Color(0xFF6A3E1E)
    val white = Color(0xFFEFEFEF)
    val green = Color(0xFF00E676)
    val yellow = Color(0xFFFFD400)

    var courseDeg by remember { mutableStateOf(0f) }

    val gsKt = nav.speedMps?.let { it * 1.943844 } ?: 0.0
    val altFt = nav.gpsAltitudeM?.let { it * 3.28084 } ?: 0.0
    val vsiFpm = nav.vsiFpm ?: 0.0
    val hdg = ((nav.trackDeg ?: 0.0) % 360 + 360) % 360
    val pitch = nav.pitchDeg ?: 0.0
    val roll = nav.rollDeg ?: 0.0
    val turnRate = nav.turnRateDps ?: 0.0

    Column(modifier.fillMaxSize().background(bg)) {

        // TOP HALF: PFD (tapes + horizon)
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SpeedTapeG1000(
                speedKt = gsKt,
                modifier = Modifier.weight(0.22f).fillMaxHeight(),
                bg = bg, tick = white, accent = green
            )

            Box(
                modifier = Modifier
                    .weight(0.56f)
                    .fillMaxHeight()
            ) {
                AttitudeHorizonG1000(
                    pitchDeg = pitch,
                    rollDeg = roll,
                    modifier = Modifier.fillMaxSize(),
                    sky = sky,
                    ground = ground,
                    tick = white,
                    aircraft = yellow
                )

                // Small turn coordinator overlay (bottom)
                TurnCoordinatorG1000(
                    turnRateDps = turnRate,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                        .height(44.dp)
                        .fillMaxWidth(0.70f),
                    tick = white,
                    accent = green
                )

                // Minimal header info (like a strip)
                Text(
                    "GS ${gsKt.roundToInt()}KT   HDG ${hdg.roundToInt()}°",
                    color = green,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 4.dp),
                    textAlign = TextAlign.Center
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)   // ✅ valid ONLY here
                        .padding(8.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(10.dp))
                        .clickable { onCalibrate() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("CAL", color = Color(0xFF00E676))
                }

            }

            AltTapeG1000(
                altitudeFt = altFt,
                vsiFpm = vsiFpm,
                modifier = Modifier.weight(0.22f).fillMaxHeight(),
                bg = bg, tick = white, accent = green
            )


        }


        // BOTTOM HALF: HSI
        HsiG1000(
            headingDeg = hdg.toFloat(),
            courseDeg = courseDeg,
            cdiDots = cdiFromCourseAndTrack(courseDeg.toDouble(), hdg),
            onCourseMinus = { courseDeg = (courseDeg - 1f + 360f) % 360f },
            onCoursePlus = { courseDeg = (courseDeg + 1f) % 360f },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp),
            bg = bg,
            tick = white,
            accent = green
        )
    }
}

private fun cdiFromCourseAndTrack(courseDeg: Double, trackDeg: Double): Float {
    val err = (trackDeg - courseDeg + 540.0) % 360.0 - 180.0
    val dots = (err / 2.0).coerceIn(-5.0, 5.0)
    return dots.toFloat()
}

@Composable
private fun SpeedTapeG1000(
    speedKt: Double,
    modifier: Modifier,
    bg: Color,
    tick: Color,
    accent: Color
) {
    TapeG1000(
        title = "IAS",
        value = speedKt,
        unit = "KT",
        majorStep = 10.0,
        minorStep = 5.0,
        windowLabel = speedKt.roundToInt().toString(),
        modifier = modifier,
        bg = bg,
        tick = tick,
        accent = accent
    )
}

@Composable
private fun AltTapeG1000(
    altitudeFt: Double,
    vsiFpm: Double,
    modifier: Modifier,
    bg: Color,
    tick: Color,
    accent: Color
) {
    Row(modifier) {
        TapeG1000(
            title = "ALT",
            value = altitudeFt,
            unit = "FT",
            majorStep = 100.0,
            minorStep = 50.0,
            windowLabel = altitudeFt.roundToInt().toString(),
            modifier = Modifier.weight(0.75f).fillMaxHeight(),
            bg = bg,
            tick = tick,
            accent = accent
        )
        VsiBarG1000(
            vsiFpm = vsiFpm,
            modifier = Modifier.weight(0.25f).fillMaxHeight(),
            bg = bg,
            tick = tick,
            accent = accent
        )
    }
}

@Composable
private fun VsiBarG1000(
    vsiFpm: Double,
    modifier: Modifier,
    bg: Color,
    tick: Color,
    accent: Color
) {
    val v = vsiFpm.coerceIn(-3000.0, 3000.0)
    Canvas(modifier.background(bg)) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        val scale = h * 0.45f

        // ticks
        val marks = listOf(-2000, -1000, -500, 0, 500, 1000, 2000)
        for (m in marks) {
            val y = mid - (m / 3000f) * scale
            drawLine(
                color = tick,
                start = Offset(w * 0.20f, y),
                end = Offset(w * 0.85f, y),
                strokeWidth = if (m % 1000 == 0) 3f else 2f
            )
        }

        // pointer triangle
        val yPtr = mid - (v / 3000f).toFloat() * scale
        val p = Path().apply {
            moveTo(w * 0.95f, yPtr)
            lineTo(w * 0.60f, yPtr - 12f)
            lineTo(w * 0.60f, yPtr + 12f)
            close()
        }
        drawPath(p, color = accent)
    }
}

@Composable
private fun TapeG1000(
    title: String,
    value: Double,
    unit: String,
    majorStep: Double,
    minorStep: Double,
    windowLabel: String,
    modifier: Modifier,
    bg: Color,
    tick: Color,
    accent: Color
) {
    Box(modifier.background(bg)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cy = h / 2f

            // frame
            drawRect(color = tick.copy(alpha = 0.25f), style = Stroke(2f), size = Size(w, h))

            // ticks around current value
            val range = 10 * majorStep
            val start = value - range
            val end = value + range

            var v = floor(start / minorStep) * minorStep
            while (v <= end) {
                val dy = ((v - value) / range).toFloat() * (h * 0.45f)
                val y = cy + dy
                if (y in 0f..h) {
                    val isMajor = abs((v / majorStep) - round(v / majorStep)) < 1e-6
                    val x0 = if (isMajor) w * 0.50f else w * 0.60f
                    val sw = if (isMajor) 4f else 2f
                    drawLine(color = tick, start = Offset(x0, y), end = Offset(w * 0.95f, y), strokeWidth = sw)
                }
                v += minorStep
            }

            // window box
            val boxH = h * 0.14f
            drawRect(color = bg, topLeft = Offset(0f, cy - boxH / 2), size = Size(w, boxH))
            drawRect(color = tick.copy(alpha = 0.6f), style = Stroke(2f), topLeft = Offset(0f, cy - boxH / 2), size = Size(w, boxH))

            // pointer caret (like G1000)
            val caret = Path().apply {
                moveTo(w * 0.02f, cy)
                lineTo(w * 0.12f, cy - 10f)
                lineTo(w * 0.12f, cy + 10f)
                close()
            }
            drawPath(caret, color = accent)
        }

        // title + window label
        Column(
            Modifier
                .fillMaxSize()
                .padding(6.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, color = accent, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(
                "$windowLabel",
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(unit, color = accent, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AttitudeHorizonG1000(
    pitchDeg: Double,
    rollDeg: Double,
    modifier: Modifier,
    sky: Color,
    ground: Color,
    tick: Color,
    aircraft: Color
) {
    val pixelsPerDeg = 8f

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val radius = min(w, h) * 0.46f

        val clipPath = Path().apply {
            addOval(
                androidx.compose.ui.geometry.Rect(
                    cx - radius,
                    cy - radius,
                    cx + radius,
                    cy + radius
                )
            )
        }

        // World (sky/ground) moves, aircraft stays fixed
        clipPath(clipPath) {
            withTransform({
                rotate(-rollDeg.toFloat(), pivot = Offset(cx, cy))
                translate(0f, (pitchDeg * pixelsPerDeg).toFloat())
            }) {
                drawRect(sky, Offset(0f, -h), Size(w, h + cy))
                drawRect(ground, Offset(0f, cy), Size(w, h))

                // Horizon line
                drawLine(tick, Offset(0f, cy), Offset(w, cy), 5f)

                // Pitch ladder
                val ladder = listOf(5, 10, 15, 20)
                val half = w * 0.18f
                for (p in ladder) {
                    val yUp = cy - p * pixelsPerDeg
                    val yDn = cy + p * pixelsPerDeg
                    drawLine(tick, Offset(cx - half, yUp), Offset(cx + half, yUp), 3f)
                    drawLine(tick, Offset(cx - half, yDn), Offset(cx + half, yDn), 3f)
                }
            }
        }

        // Bezel ring
        drawCircle(
            color = tick.copy(alpha = 0.8f),
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(3f)
        )

        // Aircraft symbol (fixed)
        val wing = w * 0.14f
        drawLine(aircraft, Offset(cx - wing, cy), Offset(cx + wing, cy), 7f)
        drawLine(aircraft, Offset(cx, cy), Offset(cx, cy + h * 0.06f), 7f)

        // Roll pointer
        val tri = Path().apply {
            moveTo(cx, cy - radius - 8f)
            lineTo(cx - 10f, cy - radius + 12f)
            lineTo(cx + 10f, cy - radius + 12f)
            close()
        }
        drawPath(tri, tick)
    }
}



@Composable
private fun TurnCoordinatorG1000(
    turnRateDps: Double,
    modifier: Modifier,
    tick: Color,
    accent: Color
) {
    val clamped = turnRateDps.coerceIn(-6.0, 6.0)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f

        drawLine(tick, Offset(w * 0.10f, cy), Offset(w * 0.90f, cy), 4f)

        val mark = w * 0.22f
        drawLine(tick, Offset(cx - mark, cy - 10f), Offset(cx - mark, cy + 10f), 4f)
        drawLine(tick, Offset(cx + mark, cy - 10f), Offset(cx + mark, cy + 10f), 4f)

        val x = cx + (clamped / 6.0).toFloat() * (w * 0.35f)
        drawCircle(accent, radius = 9f, center = Offset(x, cy))
    }
}

@Composable
private fun HsiG1000(
    headingDeg: Float,
    courseDeg: Float,
    cdiDots: Float,
    onCourseMinus: () -> Unit,
    onCoursePlus: () -> Unit,
    modifier: Modifier,
    bg: Color,
    tick: Color,
    accent: Color
) {
    Column(modifier.background(bg)) {

        // Course selector row (compact)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("HSI", color = tick)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("CRS ${courseDeg.roundToInt()}°", color = accent)
                Text(
                    "[-]",
                    color = tick,
                    modifier = Modifier
                        .clickable { onCourseMinus() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )

                Text(
                    "[+]",
                    color = tick,
                    modifier = Modifier
                        .clickable { onCoursePlus() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )

            }
        }

        // HSI dial
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(6.dp)
        ) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val r = min(w, h) * 0.42f

            drawCircle(tick, r, Offset(cx, cy), style = Stroke(3f))

            // compass ticks rotated by -heading
            withTransform({ rotate(-headingDeg, pivot = Offset(cx, cy)) }) {
                for (d in 0 until 360 step 30) {
                    val ang = Math.toRadians(d.toDouble())
                    val x0 = cx + (r * 0.82f * cos(ang)).toFloat()
                    val y0 = cy + (r * 0.82f * sin(ang)).toFloat()
                    val x1 = cx + (r * cos(ang)).toFloat()
                    val y1 = cy + (r * sin(ang)).toFloat()
                    drawLine(tick, Offset(x0, y0), Offset(x1, y1), 4f)
                }
            }

            // heading bug pointer at top
            val bug = Path().apply {
                moveTo(cx, cy - r - 8f)
                lineTo(cx - 10f, cy - r + 12f)
                lineTo(cx + 10f, cy - r + 12f)
                close()
            }
            drawPath(bug, accent)

            // course arrow (relative to heading)
            val relCourse = (courseDeg - headingDeg)
            withTransform({ rotate(relCourse, pivot = Offset(cx, cy)) }) {
                drawLine(accent, Offset(cx, cy), Offset(cx, cy - r * 0.78f), 5f)
                drawLine(accent, Offset(cx, cy - r * 0.78f), Offset(cx - 12f, cy - r * 0.66f), 5f)
                drawLine(accent, Offset(cx, cy - r * 0.78f), Offset(cx + 12f, cy - r * 0.66f), 5f)
            }

            // CDI needle
            val dots = cdiDots.coerceIn(-5f, 5f)
            val x = cx + dots * (r * 0.06f)
            drawLine(tick, Offset(x, cy - r * 0.45f), Offset(x, cy + r * 0.45f), 5f)

            // center aircraft
            drawLine(tick, Offset(cx - 34f, cy), Offset(cx + 34f, cy), 6f)
            drawLine(tick, Offset(cx, cy), Offset(cx, cy + 18f), 6f)
        }
    }
}
