package com.adityaapte.virtualcockpit

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.*
import kotlinx.coroutines.Job
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.layout.offset
import org.maplibre.geojson.LineString
import org.maplibre.android.style.layers.LineLayer
import androidx.compose.ui.unit.sp




/**
 * MUST match MapScreen.kt asset style.
 */
private const val HSI_INSET_STYLE_URI = "asset://styles/offlinedarkstyle.json"

// HSI inset airports (separate IDs so we don't collide with Map tab IDs)
private const val HSI_AIRPORT_SRC_ID = "hsi_airport_src"
private const val HSI_DIRECT_TO_SRC_ID = "hsi_direct_to_src"
private const val HSI_DIRECT_TO_LAYER_ID = "hsi_direct_to_layer"

private const val HSI_AIRPORT_DOT_LAYER_ID = "hsi_airport_dot_layer"
private const val HSI_AIRPORT_LABEL_LAYER_ID = "hsi_airport_label_layer"

@Composable
fun GlassCockpitPfdScreen(
    nav: NavData,
    modifier: Modifier = Modifier,
    onCalibrate: () -> Unit
) {
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

    // Keep last valid fix so inset doesn't jump to (0,0) or go null and show grey forever
    var lastLat by remember { mutableStateOf<Double?>(null) }
    var lastLon by remember { mutableStateOf<Double?>(null) }

    LaunchedEffect(nav.lat, nav.lon) {
        val la = nav.lat
        val lo = nav.lon

        // Ignore bogus "no fix" values (0,0) which would keep inset grey forever
        val isBogusZero = la != null && lo != null && abs(la) < 0.0001 && abs(lo) < 0.0001

        if (la != null && lo != null && !isBogusZero) {
            lastLat = la
            lastLon = lo
        }
    }

    Column(modifier.fillMaxSize().background(bg)) {

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SpeedTapeG1000(
                speedKt = gsKt,
                modifier = Modifier
                    .weight(0.22f)
                    .fillMaxHeight(),
                bg = bg,
                tick = white,
                accent = green
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

                var turnSmooth by remember { mutableStateOf(0.0) }
                LaunchedEffect(turnRate) {
                    turnSmooth = turnSmooth * 0.85 + turnRate * 0.15
                }


                TurnCoordinatorG1000(
                    turnRateDps = turnSmooth,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                        .height(44.dp)
                        .fillMaxWidth(0.70f),
                    tick = white,
                    accent = green
                )

                Text(
                    "GS ${gsKt.roundToInt()}KT",
                    color = green,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 4.dp),
                    textAlign = TextAlign.Center
                )

            }

            AltTapeG1000(
                altitudeFt = altFt,
                vsiFpm = vsiFpm,
                modifier = Modifier
                    .weight(0.22f)
                    .fillMaxHeight(),
                bg = bg,
                tick = white,
                accent = green
            )
        }

        // ---- GPS NAV (Direct-To) support ----
        val gpsMode = (nav.navSource == NavSource.GPS) &&
                nav.directTo.isActive &&
                (nav.directTo.from != null) &&
                (nav.directTo.to != null) &&
                (lastLat != null) &&
                (lastLon != null)

        val legCourse: Float? = if (gpsMode) {
            val f = nav.directTo.from!!
            val t = nav.directTo.to!!
            bearingDeg(f.lat, f.lon, t.lat, t.lon).toFloat()
        } else null

// Auto-set CRS to leg course when GPS mode is active
        LaunchedEffect(gpsMode, legCourse) {
            if (gpsMode && legCourse != null) {
                courseDeg = ((legCourse % 360f) + 360f) % 360f
            }
        }

// Compute CDI dots
        val cdiDotsFinal: Float = if (gpsMode) {
            val f = nav.directTo.from!!
            val t = nav.directTo.to!!
            val xteNm = crossTrackErrorNm(
                curLat = lastLat!!, curLon = lastLon!!,
                fromLat = f.lat, fromLon = f.lon,
                toLat = t.lat, toLon = t.lon
            )
            cdiDotsFromXteNm(xteNm, fullScaleNm = 2.0) // start with ±2NM full-scale
        } else {
            cdiFromCourseAndTrack(courseDeg.toDouble(), hdg) // current turn-cue behavior
        }

        HsiG1000(
            headingDeg = hdg.toFloat(),
            courseDeg = courseDeg,
            cdiDots = cdiDotsFinal,
            onCourseMinus = {
                if (!gpsMode) courseDeg = (courseDeg - 1f + 360f) % 360f
            },
            onCoursePlus = {
                if (!gpsMode) courseDeg = (courseDeg + 1f) % 360f
            },
            lat = lastLat,
            lon = lastLon,
            directTo = nav.directTo,
            navSource = nav.navSource,
            groundSpeedKt = gsKt,
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

fun cdiFromCourseAndTrack(courseDeg: Double, trackDeg: Double): Float {
    // positive => course is to the right of track/heading
    val err = (courseDeg - trackDeg + 540.0) % 360.0 - 180.0
    return (err / 2.0).coerceIn(-5.0, 5.0).toFloat()
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
        val useFl = altitudeFt >= 18000.0
        val altWindow = if (useFl) {
            val fl = (altitudeFt / 100.0).roundToInt()
            "FL" + fl.toString().padStart(3, '0')   // e.g. FL350
        } else {
            altitudeFt.roundToInt().toString()      // e.g. 9500
        }
        val unitLabel = if (useFl) "FL" else "FT"
        val showAltLabel: (Double) -> Boolean = { v ->
            if (!useFl) true else abs(v % 1000.0) < 1e-6
        }
        val fmtAltLabel: (Double) -> String = { v ->
            if (!useFl) v.roundToInt().toString()
            else (v / 1000.0).roundToInt().toString()   // 35000 -> "35"
        }

        TapeG1000(
            title = "ALT",
            value = altitudeFt,
            unit = unitLabel,          // <-- IMPORTANT (was "FT")
            majorStep = 100.0,
            minorStep = 50.0,
            windowLabel = altWindow,
            modifier = Modifier
                .weight(0.75f)
                .fillMaxHeight(),
            bg = bg,
            tick = tick,
            accent = accent,
            formatMajorLabel = fmtAltLabel,
            showMajorLabel = showAltLabel
        )

        VsiBarG1000(
            vsiFpm = vsiFpm,
            modifier = Modifier
                .weight(0.25f)
                .fillMaxHeight(),
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
    accent: Color,
    formatMajorLabel: (Double) -> String = { it.roundToInt().toString() },
    showMajorLabel: (Double) -> Boolean = { true }
)
 {
    Box(modifier.background(bg)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cy = h / 2f
            val scalePx = h * 0.45f

            drawRect(color = tick.copy(alpha = 0.25f), style = Stroke(2f), size = Size(w, h))

            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = (h * 0.060f).coerceIn(13f, 22f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textAlign = Paint.Align.LEFT
            }
            fun drawFittedLeftText(text: String, x: Float, y: Float, maxWidth: Float) {
                val original = labelPaint.textSize
                val tw = labelPaint.measureText(text)
                if (tw > maxWidth) {
                    val scale = maxWidth / tw
                    labelPaint.textSize = (original * scale).coerceAtLeast(11f)
                }
                drawContext.canvas.nativeCanvas.drawText(text, x, y, labelPaint)
                labelPaint.textSize = original
            }


            val range = 10 * majorStep
            val start = value - range
            val end = value + range

            var v = floor(start / minorStep) * minorStep
            while (v <= end) {
                val dy = ((v - value) / range).toFloat() * scalePx
                val y = cy - dy

                if (y in 0f..h) {
                    val isMajor = abs((v / majorStep) - round(v / majorStep)) < 1e-6
                    val x0 = if (isMajor) w * 0.52f else w * 0.62f
                    val sw = if (isMajor) 4f else 2f

                    drawLine(
                        color = tick,
                        start = Offset(x0, y),
                        end = Offset(w * 0.95f, y),
                        strokeWidth = sw
                    )

                    if (isMajor && showMajorLabel(v)) {
                        val label = formatMajorLabel(v)
                        val leftGutterMax = w * 0.40f
                        drawFittedLeftText(
                            text = label,
                            x = w * 0.08f,
                            y = y + (labelPaint.textSize * 0.35f),
                            maxWidth = leftGutterMax
                        )
                    }

                }
                v += minorStep
            }

            val boxH = h * 0.14f
            drawRect(color = bg, topLeft = Offset(0f, cy - boxH / 2), size = Size(w, boxH))
            drawRect(
                color = tick.copy(alpha = 0.6f),
                style = Stroke(2f),
                topLeft = Offset(0f, cy - boxH / 2),
                size = Size(w, boxH)
            )

            val caret = Path().apply {
                moveTo(w * 0.02f, cy)
                lineTo(w * 0.12f, cy - 10f)
                lineTo(w * 0.12f, cy + 10f)
                close()
            }
            drawPath(caret, color = accent)
            val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(0, 230, 118) // just used for measuring; actual color done via drawContext with paint swap
                textSize = (h * 0.055f).coerceIn(12f, 20f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }

            fun drawHudCentered(text: String, cxText: Float, cyText: Float, paint: Paint, padX: Float, padY: Float, textColor: Int) {
                val tw = paint.measureText(text)
                val th = paint.textSize
                // backing rect
                drawRect(
                    color = bg,
                    topLeft = Offset(cxText - tw / 2f - padX, cyText - th - padY),
                    size = Size(tw + 2f * padX, th + 2f * padY)
                )
                paint.color = textColor
                drawContext.canvas.nativeCanvas.drawText(text, cxText, cyText, paint)
            }

            val topY = (labelPaint.textSize * 1.2f).coerceAtLeast(18f)
            val botY = h - 10f
            val centerY = cy + (labelPaint.textSize * 0.35f)

            val titlePaint = Paint(hudPaint).apply { textSize = (h * 0.050f).coerceIn(12f, 18f) }
            val unitPaint  = Paint(hudPaint).apply { textSize = (h * 0.050f).coerceIn(12f, 18f) }
            val valPaint   = Paint(hudPaint).apply { textSize = (h * 0.070f).coerceIn(16f, 26f) }

// Title (top)
            drawHudCentered(title, w * 0.50f, topY, titlePaint, padX = 10f, padY = 6f, textColor = android.graphics.Color.rgb(0, 230, 118))
// Value (middle window)
            drawHudCentered(windowLabel, w * 0.50f, centerY, valPaint, padX = 12f, padY = 8f, textColor = android.graphics.Color.WHITE)
// Unit (bottom)
            drawHudCentered(unit, w * 0.50f, botY, unitPaint, padX = 10f, padY = 6f, textColor = android.graphics.Color.rgb(0, 230, 118))

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

        val clipOval = Path().apply {
            addOval(
                Rect(
                    cx - radius, cy - radius, cx + radius, cy + radius
                )
            )
        }

        clipPath(clipOval) {
            withTransform({
                rotate(-rollDeg.toFloat(), pivot = Offset(cx, cy))
                translate(0f, (pitchDeg * pixelsPerDeg).toFloat())
            }) {
                drawRect(sky, Offset(0f, -h), Size(w, h + cy))
                drawRect(ground, Offset(0f, cy), Size(w, h))
                drawLine(tick, Offset(0f, cy), Offset(w, cy), 5f)

                // --- Pitch ladder (numbers + major/minor ticks) ---
                val pitchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE
                    textSize = (radius * 0.09f).coerceIn(12f, 18f)
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                }

// draw +/- pitch lines every 5°, with longer lines at 10/20/30
                val maxPitch = 30
                val majorLen = w * 0.22f
                val minorLen = w * 0.16f
                val textGap = w * 0.015f

                for (p in 5..maxPitch step 5) {
                    val isMajor = (p % 10 == 0)
                    val halfLen = if (isMajor) majorLen else minorLen
                    val stroke = if (isMajor) 4f else 3f

                    val yUp = cy - p * pixelsPerDeg
                    val yDn = cy + p * pixelsPerDeg

                    // Lines (above horizon)
                    drawLine(tick, Offset(cx - halfLen, yUp), Offset(cx + halfLen, yUp), stroke)
                    // Lines (below horizon)
                    drawLine(tick, Offset(cx - halfLen, yDn), Offset(cx + halfLen, yDn), stroke)

                    // Small end-ticks to make it look more "ladder-like"
                    val endTick = if (isMajor) 10f else 7f
                    drawLine(tick, Offset(cx - halfLen, yUp), Offset(cx - halfLen, yUp + endTick), stroke)
                    drawLine(tick, Offset(cx + halfLen, yUp), Offset(cx + halfLen, yUp + endTick), stroke)
                    drawLine(tick, Offset(cx - halfLen, yDn), Offset(cx - halfLen, yDn - endTick), stroke)
                    drawLine(tick, Offset(cx + halfLen, yDn), Offset(cx + halfLen, yDn - endTick), stroke)


                    // Numbers at 10/20/30 (both sides)

                    if (isMajor) {
                        val label = p.toString()

                        // left label (right-aligned)
                        pitchPaint.textAlign = Paint.Align.RIGHT
                        drawContext.canvas.nativeCanvas.drawText(
                            label,
                            cx - halfLen - textGap,
                            yUp + (pitchPaint.textSize * 0.35f),
                            pitchPaint
                        )
                        drawContext.canvas.nativeCanvas.drawText(
                            label,
                            cx - halfLen - textGap,
                            yDn + (pitchPaint.textSize * 0.35f),
                            pitchPaint
                        )

                        // right label (left-aligned)
                        pitchPaint.textAlign = Paint.Align.LEFT
                        drawContext.canvas.nativeCanvas.drawText(
                            label,
                            cx + halfLen + textGap,
                            yUp + (pitchPaint.textSize * 0.35f),
                            pitchPaint
                        )
                        drawContext.canvas.nativeCanvas.drawText(
                            label,
                            cx + halfLen + textGap,
                            yDn + (pitchPaint.textSize * 0.35f),
                            pitchPaint
                        )
                    }
                }

// Optional finer tick marks at 2.5° (subtle)
// If you want this, uncomment:

                for (p in 3..(maxPitch * 2) step 5) { // 2.5, 7.5, 12.5...
                    val deg = p / 2f
                    val yUp = cy - deg * pixelsPerDeg
                    val yDn = cy + deg * pixelsPerDeg
                    val halfLen = w * 0.10f
                    drawLine(tick.copy(alpha = 0.45f), Offset(cx - halfLen, yUp), Offset(cx + halfLen, yUp), 2f)
                    drawLine(tick.copy(alpha = 0.45f), Offset(cx - halfLen, yDn), Offset(cx + halfLen, yDn), 2f)
                }


            }
        }

        drawCircle(
            color = tick.copy(alpha = 0.8f),
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(3f)
        )
        // --- Bank angle scale (fixed, not rotating) ---
        val arcR = radius * 1.10f
        drawArc(
            color = tick.copy(alpha = 0.55f),
            startAngle = 210f,     // -150°
            sweepAngle = 120f,     // to -30°
            useCenter = false,
            topLeft = Offset(cx - arcR, cy - arcR),
            size = Size(arcR * 2f, arcR * 2f),
            style = Stroke(3f)
        )

        val bankMarks = listOf(-60, -45, -30, -20, -10, 0, 10, 20, 30, 45, 60)
        for (deg in bankMarks) {
            val a = Math.toRadians((-90.0 + deg).toDouble())
            val isBig = (abs(deg) == 60)
            val isMid = (abs(deg) == 45)
            val isMajor = (abs(deg) == 30)
            val isMinor = (abs(deg) == 10 || abs(deg) == 20)
            val isZero = (deg == 0)

            val len = when {
                isZero -> radius * 0.16f        // <-- longer center reference
                isBig  -> radius * 0.14f
                isMid  -> radius * 0.11f
                isMajor-> radius * 0.10f
                else   -> radius * 0.07f
            }
            val sw = when {
                isZero -> 5f                     // <-- thicker center reference
                isBig || isMid || isMajor -> 4f
                else -> 3f
            }

            val x0 = cx + (arcR * cos(a)).toFloat()
            val y0 = cy + (arcR * sin(a)).toFloat()
            val x1 = cx + ((arcR + len) * cos(a)).toFloat()
            val y1 = cy + ((arcR + len) * sin(a)).toFloat()


            drawLine(
                color = tick,
                start = Offset(x0, y0),
                end = Offset(x1, y1),
                strokeWidth = sw
            )
        }

        // --- Current bank bug (moves with rollDeg) ---
        val bankBugColor = Color(0xFF00E676) // same green as your UI theme
        val rollClamped = rollDeg.toFloat().coerceIn(-60f, 60f)
        val aBug = Math.toRadians((-90.0 + rollClamped).toDouble())

        val ux = cos(aBug).toFloat()
        val uy = sin(aBug).toFloat()
// tangent vector (perpendicular)
        val tx = -uy
        val ty = ux

        val bugBaseR = arcR + radius * 0.30f   // sits just outside the ticks
        val bugH = radius * 0.045f            // bug height
        val bugHalfW = radius * 0.030f        // bug half width

        val bx = cx + bugBaseR * ux
        val by = cy + bugBaseR * uy

        val tip = Offset(bx - bugH * ux, by - bugH * uy)
        val left = Offset(bx - bugHalfW * tx, by - bugHalfW * ty)
        val right = Offset(bx + bugHalfW * tx, by + bugHalfW * ty)

        val bugPath = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(left.x, left.y)
            lineTo(right.x, right.y)
            close()
        }
        drawPath(bugPath, bankBugColor)

        // --- Bank angle labels (10/20/30/45/60) ---
        val bankLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = (radius * 0.075f).coerceIn(12f, 18f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val labelDegs = listOf(10, 20, 30, 45, 60)
        val labelR = radius * 1.3f // slightly inside the arc

        for (d in labelDegs) {
            for (sign in listOf(-1, 1)) {
                val deg = d * sign
                val a = Math.toRadians((-90.0 + deg).toDouble())
                val lx = cx + (labelR * cos(a)).toFloat()
                val ly = cy + (labelR * sin(a)).toFloat()
                drawContext.canvas.nativeCanvas.drawText(
                    d.toString(), // show absolute value
                    lx,
                    ly + (bankLabelPaint.textSize * 0.35f),
                    bankLabelPaint
                )
            }
        }



        val wing = w * 0.14f
        drawLine(aircraft, Offset(cx - wing, cy), Offset(cx + wing, cy), 7f)
        drawLine(aircraft, Offset(cx, cy), Offset(cx, cy + h * 0.06f), 7f)

        // --- Current bank bug (rotates with rollDeg) ---
        val pointerW = radius * 0.045f
        val pointerH = radius * 0.060f
        val topY = cy - arcR

        val bankBug = Path().apply {
            moveTo(cx, topY - pointerH)
            lineTo(cx - pointerW, topY + pointerH * 0.30f)
            lineTo(cx + pointerW, topY + pointerH * 0.30f)
            close()
        }

        withTransform({
            // rotate the bug to current bank angle
            rotate(degrees = rollDeg.toFloat().coerceIn(-60f, 60f), pivot = Offset(cx, cy))

        }) {
            drawPath(bankBug, Color(0xFF00E676)) // green bug
        }

        // --- Fixed top index notch (reference) ---
        drawLine(
            color = tick,
            start = Offset(cx, (cy - arcR) - radius * 0.02f),
            end = Offset(cx, (cy - arcR) + radius * 0.04f),
            strokeWidth = 4f
        )



// small notch line into the arc so it's easy to "read"
        drawLine(
            color = tick,
            start = Offset(cx, topY + pointerH * 0.30f),
            end = Offset(cx, topY + pointerH * 0.75f),
            strokeWidth = 4f
        )

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

/**
 * HSI inset map: uses SAME AirportRepository + queryVisible pattern as MapScreen,
 * but always labels with CODE ONLY and hides base map labels.
 */
@Composable
private fun HsiMovingMapInset(
    lat: Double?,
    lon: Double?,
    headingDeg: Float,
    zoom: Float = 10.5f,
    diameter: Dp,
    modifier: Modifier = Modifier,
    directTo: DirectToPlan,
    navSource: NavSource
) {

    // 1. Check for invalid coordinates ONCE at the top
    val isBogusZero = lat != null && lon != null &&
            abs(lat) < 0.0001 && abs(lon) < 0.0001

    val hasValidCoords = lat != null && lon != null && !isBogusZero

    // 2. Show placeholder if no valid GPS - DON'T create MapView yet
    if (!hasValidCoords) {
        Box(
            modifier = modifier
                .size(diameter)
                .clip(CircleShape)
                .background(Color(0xFF05070A))
        ) {
            Text(
                "NO GPS",
                color = Color(0xFF666666),
                modifier = Modifier.align(Alignment.Center)
            )
        }
        return
    }

    // 3. Now we know we have valid coordinates - create everything
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { AirportRepository.get(ctx) }

    // CRITICAL: Only create MapView when we have valid coordinates
    val mapView = rememberMapViewWithLifecycle()

    var mapLibre by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var didInit by remember { mutableStateOf(false) }

    // 4. Seed database once
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { repo.ensureSeededFromAssets() }
        }
    }

    // 5. Main container
    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .background(Color(0xFF05070A))
            .graphicsLayer { clip = true },
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView }
        )
    }

    // 6. Initialize map + style ONCE
    LaunchedEffect(mapView) {
        if (didInit) return@LaunchedEffect
        didInit = true

        mapView.getMapAsync { map ->
            mapLibre = map

            map.uiSettings.apply {
                isCompassEnabled = false
                isRotateGesturesEnabled = false
                isZoomGesturesEnabled = false
                isScrollGesturesEnabled = false
                isTiltGesturesEnabled = false
                isDoubleTapGesturesEnabled = false
                isQuickZoomGesturesEnabled = false
                isAttributionEnabled = false
                isLogoEnabled = false
            }

            styleReady = false

            // Try to load style with error handling
            try {
                map.setStyle(Style.Builder().fromUri(HSI_INSET_STYLE_URI)) { style ->
                    setupHsiInsetStyle(style, directTo)

                    hideAllSymbolLabelsExcept(style, keepLayerId = HSI_AIRPORT_LABEL_LAYER_ID)
                    styleReady = true
                    // 2b) Airports label layer (CODE labels)
                    if (style.getLayer(HSI_AIRPORT_LABEL_LAYER_ID) == null) {
                        val lbl = SymbolLayer(HSI_AIRPORT_LABEL_LAYER_ID, HSI_AIRPORT_SRC_ID).withProperties(
                            textField(get("label")),
                            textSize(12f),
                            textColor("#CFE9D8"),
                            textHaloColor("#0B0F14"),
                            textHaloWidth(1.2f),
                            textOffset(arrayOf(0f, 1.2f)),
                            textAnchor("top"),
                            textAllowOverlap(false),
                            textIgnorePlacement(false)
                        )
                        style.addLayerAbove(lbl, HSI_AIRPORT_DOT_LAYER_ID)
                    }


                    // Initial camera position with valid coordinates
                    val cam = CameraPosition.Builder()
                        .target(LatLng(lat, lon))
                        .zoom(zoom.toDouble())
                        .bearing(headingDeg.toDouble())
                        .tilt(0.0)
                        .build()
                    map.moveCamera(CameraUpdateFactory.newCameraPosition(cam))

                    // Initial airport refresh
                    refreshHsiInsetAirports(repo, scope, map)

                    // Add idle listener for future updates
                    map.addOnCameraIdleListener {
                        refreshHsiInsetAirports(repo, scope, map)
                    }
                }
            } catch (e: Exception) {
                // If style fails to load, at least the grey background shows
                android.util.Log.e("HsiInset", "Style load failed", e)
            }
        }
    }

    // 7. Update camera when coordinates/heading/zoom change (after initial setup)
    LaunchedEffect(lat, lon, headingDeg, zoom, styleReady) {
        val m = mapLibre ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect

        val cam = CameraPosition.Builder()
            .target(LatLng(lat!!, lon!!))
            .zoom(zoom.toDouble())
            .bearing(headingDeg.toDouble())
            .tilt(0.0)
            .build()

        m.animateCamera(CameraUpdateFactory.newCameraPosition(cam), 300)


        // Refresh airports after camera moves
        delay(350)
        refreshHsiInsetAirports(repo, scope, m)

    }
    LaunchedEffect(directTo, navSource, styleReady) {
        val m = mapLibre ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect

        val style = m.style ?: return@LaunchedEffect
        val src = style.getSourceAs<GeoJsonSource>(HSI_DIRECT_TO_SRC_ID) ?: return@LaunchedEffect

        // If you only want the line in GPS mode:
         if (navSource != NavSource.GPS || !directTo.isActive) {
             src.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
             return@LaunchedEffect
         }

        src.setGeoJson(directToFeatureCollection(directTo))
    }
}

private fun setupHsiInsetStyle(style: Style, directTo: DirectToPlan) {

    // 1) Airports source
    if (style.getSource(HSI_AIRPORT_SRC_ID) == null) {
        style.addSource(
            GeoJsonSource(
                HSI_AIRPORT_SRC_ID,
                FeatureCollection.fromFeatures(emptyArray())
            )
        )
    }

    // 2) Airports dot layer
    if (style.getLayer(HSI_AIRPORT_DOT_LAYER_ID) == null) {
        style.addLayer(
            CircleLayer(HSI_AIRPORT_DOT_LAYER_ID, HSI_AIRPORT_SRC_ID).withProperties(
                circleColor("#00E676"),
                circleOpacity(0.90f),
                circleStrokeColor("#0B0F14"),
                circleStrokeWidth(1.2f),
                circleRadius(3.5f)
            )
        )
    }

    // 3) Direct-To source
    if (style.getSource(HSI_DIRECT_TO_SRC_ID) == null) {
        style.addSource(GeoJsonSource(HSI_DIRECT_TO_SRC_ID, directToFeatureCollection(directTo)))
    }

    // 4) Direct-To layer (add above airports now that airport layer exists)
    if (style.getLayer(HSI_DIRECT_TO_LAYER_ID) == null) {
        val layer = LineLayer(HSI_DIRECT_TO_LAYER_ID, HSI_DIRECT_TO_SRC_ID).withProperties(
            lineColor("#FF00FF"),
            lineWidth(4f),
            lineCap("round"),
            lineJoin("round")
        )
        style.addLayerAbove(layer, HSI_AIRPORT_DOT_LAYER_ID)
    }
}


private fun hideAllSymbolLabelsExcept(style: Style, keepLayerId: String) {
    // Removes city/road/poi labels in the inset; keeps only our airport label layer
    for (layer in style.layers) {
        if (layer is SymbolLayer && layer.id != keepLayerId) {
            layer.setProperties(visibility("none"))
        }
    }
}

private fun refreshHsiInsetAirports(
    repo: AirportRepository,
    scope: CoroutineScope,
    map: MapLibreMap
) {
    val style = map.style ?: return
    val bounds = map.projection.visibleRegion.latLngBounds
    val zoom = map.cameraPosition.zoom

    val sw = bounds.southWest
    val ne = bounds.northEast

    scope.launch {
        val rows = withContext(Dispatchers.IO) {
            repo.queryVisible(
                south = sw.latitude,
                west = sw.longitude,
                north = ne.latitude,
                east = ne.longitude,
                zoom = zoom
            )
        }

        val features = rows.mapNotNull { a ->
            val iata = a.iata?.trim().orEmpty()
            val icao = a.icao?.trim().orEmpty()
            val code = when {
                iata.isNotBlank() -> iata
                icao.isNotBlank() -> icao
                else -> ""
            }
            if (code.isBlank()) return@mapNotNull null

            Feature.fromGeometry(Point.fromLngLat(a.lon, a.lat)).also { f ->
                f.addStringProperty("label", a.name) // names only
            }
        }

        withContext(Dispatchers.Main) {
            style.getSourceAs<GeoJsonSource>(HSI_AIRPORT_SRC_ID)
                ?.setGeoJson(FeatureCollection.fromFeatures(features))
        }
    }
}

@Composable
private fun HsiG1000(
    headingDeg: Float,
    courseDeg: Float,
    cdiDots: Float,
    onCourseMinus: () -> Unit,
    onCoursePlus: () -> Unit,
    lat: Double?,
    lon: Double?,
    directTo: DirectToPlan,
    navSource: NavSource,
    modifier: Modifier,
    bg: Color,
    tick: Color,
    groundSpeedKt: Double?,
    accent: Color
)
 {
    // Add zoom state
    var mapZoom by remember { mutableStateOf(10.5f) }

    Column(modifier.background(bg)) {

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("HSI", color = tick)

            val show = (navSource == NavSource.GPS) &&
                    directTo.isActive &&
                    (directTo.to != null) &&
                    (lat != null) && (lon != null)

            if (show) {
                val t = directTo.to!!
                val dtg = distanceNm(lat!!, lon!!, t.lat, t.lon)
                val eta = formatEtaText(dtg, groundSpeedKt)
                Text(
                    "DTG ${"%.1f".format(dtg)}NM  ETA $eta",
                    color = tick,
                    fontSize = 12.sp
                )
            } else {
                Spacer(Modifier.width(1.dp))
            }
        }


        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            val minDim = minOf(this.maxWidth, this.maxHeight)
            val insetDiameter = minDim * 0.84f

            Text(
                text = "HDG ${headingDeg.roundToInt()}°",
                color = accent,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 2.dp)
                    .offset(y = -10.dp)
            )

            // 1) Map underlay (moving map) - pass zoom level
            HsiMovingMapInset(
                lat = lat,
                lon = lon,
                headingDeg = headingDeg,
                zoom = mapZoom,
                diameter = insetDiameter,
                directTo = directTo,
                navSource = navSource
            )



            // 2) Tactical scrim so rose stays readable
            Box(
                modifier = Modifier
                    .size(insetDiameter)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
            )

            // LEFT: ZOOM controls
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 6.dp)
                    .offset(y = 130.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Z =${mapZoom.roundToInt()}", color = accent)
                HoldRepeatTextButton(
                    label = "[-]",
                    color = tick,
                    onTap = { mapZoom = (mapZoom - 0.5f).coerceIn(6.0f, 16.0f) },
                    onHoldStep = { mapZoom = (mapZoom - 0.5f).coerceIn(6.0f, 16.0f) }
                )
                HoldRepeatTextButton(
                    label = "[+]",
                    color = tick,
                    onTap = { mapZoom = (mapZoom + 0.5f).coerceIn(6.0f, 16.0f) },
                    onHoldStep = { mapZoom = (mapZoom + 0.5f).coerceIn(6.0f, 16.0f) }
                )
            }

// RIGHT: CRS controls (tap=1°, hold=10° repeating)
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .offset(y = 130.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("CRS ${courseDeg.roundToInt()}°", color = accent)

                HoldRepeatTextButton(
                    label = "[-]",
                    color = tick,
                    onTap = onCourseMinus,
                    onHoldStep = {
                        repeat(10) { onCourseMinus() } // tens
                    }
                )
                HoldRepeatTextButton(
                    label = "[+]",
                    color = tick,
                    onTap = onCoursePlus,
                    onHoldStep = {
                        repeat(10) { onCoursePlus() } // tens
                    }
                )
            }

            // 3) Compass overlay (tactical/clean)
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val cx = w / 2f
                val cy = h / 2f
                val r = min(w, h) * 0.42f

                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE
                    textSize = (r * 0.12f).coerceIn(14f, 24f)
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }

                // rings
                drawCircle(tick.copy(alpha = 0.90f), r, Offset(cx, cy), style = Stroke(3f))
                drawCircle(tick.copy(alpha = 0.18f), r * 0.82f, Offset(cx, cy), style = Stroke(2f))
                drawCircle(tick.copy(alpha = 0.12f), r * 0.64f, Offset(cx, cy), style = Stroke(2f))

                // rose rotated by heading
                withTransform({ rotate(-headingDeg, pivot = Offset(cx, cy)) }) {
                    for (d in 0 until 360 step 5) {
                        val ang = Math.toRadians((d - 90).toDouble())
                        val isMajor = d % 30 == 0
                        val isMid = d % 10 == 0

                        val len = when {
                            isMajor -> r * 0.20f
                            isMid -> r * 0.13f
                            else -> r * 0.07f
                        }

                        val x0 = cx + ((r - len) * cos(ang)).toFloat()
                        val y0 = cy + ((r - len) * sin(ang)).toFloat()
                        val x1 = cx + (r * cos(ang)).toFloat()
                        val y1 = cy + (r * sin(ang)).toFloat()

                        drawLine(
                            color = tick,
                            start = Offset(x0, y0),
                            end = Offset(x1, y1),
                            strokeWidth = when {
                                isMajor -> 4f
                                isMid -> 3f
                                else -> 2f
                            }
                        )

                        if (isMajor) {
                            val label = when (d) {
                                0 -> "N"
                                90 -> "E"
                                180 -> "S"
                                270 -> "W"
                                else -> (d / 10).toString()
                            }
                            val lx = cx + (r * 0.72f * cos(ang)).toFloat()
                            val ly = cy + (r * 0.72f * sin(ang)).toFloat()
                            drawContext.canvas.nativeCanvas.drawText(
                                label,
                                lx,
                                ly + (textPaint.textSize * 0.35f),
                                textPaint
                            )
                        }
                    }
                }

                // heading bug (bigger)
                val bugTop = 18f      // how tall it sticks above the ring
                val bugHalfW = 16f    // half width of the triangle base
                val bugBaseH = 18f
                // heading bug
                val bug = Path().apply {
                    moveTo(cx, cy - r - bugTop)
                    lineTo(cx - bugHalfW, cy - r + bugBaseH)
                    lineTo(cx + bugHalfW, cy - r + bugBaseH)
                    close()
                }
                drawPath(bug, accent)

                // course arrow relative to heading
                val relCourse = ((courseDeg - headingDeg + 540f) % 360f) - 180f
                withTransform({ rotate(relCourse, pivot = Offset(cx, cy)) })  {
                    drawLine(accent, Offset(cx, cy), Offset(cx, cy - r * 0.78f), 5f)
                    drawLine(accent, Offset(cx, cy - r * 0.78f), Offset(cx - 12f, cy - r * 0.66f), 5f)
                    drawLine(accent, Offset(cx, cy - r * 0.78f), Offset(cx + 12f, cy - r * 0.66f), 5f)
                }

                // CDI needle
                val dots = cdiDots.coerceIn(-5f, 5f)
                val x = cx + dots * (r * 0.06f)
                drawLine(tick, Offset(x, cy - r * 0.45f), Offset(x, cy + r * 0.45f), 5f)

                // aircraft marker
                drawLine(tick, Offset(cx - 34f, cy), Offset(cx + 34f, cy), 6f)
                drawLine(tick, Offset(cx, cy), Offset(cx, cy + 18f), 6f)

                // top index line
                drawLine(
                    color = tick.copy(alpha = 0.85f),
                    start = Offset(cx, cy - r * 1.02f),
                    end = Offset(cx, cy - r * 0.88f),
                    strokeWidth = 5f
                )
            }
        }
    }
}
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HoldRepeatTextButton(
    label: String,
    modifier: Modifier = Modifier,
    color: Color,
    onTap: () -> Unit,
    onHoldStep: () -> Unit,
    holdDelayMs: Long = 350L,
    repeatMs: Long = 110L
) {
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var downAt by remember { mutableStateOf(0L) }
    var didHold by remember { mutableStateOf(false) }

    Text(
        text = label,
        color = color,
        modifier = modifier
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .pointerInteropFilter { ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downAt = SystemClock.uptimeMillis()
                        didHold = false

                        job?.cancel()
                        job = scope.launch {
                            delay(holdDelayMs)
                            didHold = true
                            while (true) {
                                onHoldStep()
                                delay(repeatMs)
                            }
                        }
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        job?.cancel()
                        job = null

                        // If user released before holdDelay, treat as tap
                        if (!didHold) onTap()
                        true
                    }

                    else -> false
                }
            }
    )
}
private fun wrap180(deg: Double): Double {
    var d = (deg + 180.0) % 360.0
    if (d < 0) d += 360.0
    return d - 180.0
}

private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val φ1 = Math.toRadians(lat1)
    val φ2 = Math.toRadians(lat2)
    val Δλ = Math.toRadians(lon2 - lon1)

    val y = sin(Δλ) * cos(φ2)
    val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
    val θ = atan2(y, x)
    val brng = (Math.toDegrees(θ) + 360.0) % 360.0
    return brng
}

/**
 * Signed cross-track error in nautical miles.
 * Sign convention:
 *   xteNm > 0  => aircraft RIGHT of desired track (track line is LEFT)
 *   xteNm < 0  => aircraft LEFT  of desired track (track line is RIGHT)
 */
private fun crossTrackErrorNm(
    curLat: Double, curLon: Double,
    fromLat: Double, fromLon: Double,
    toLat: Double, toLon: Double
): Double {
    val Rnm = 3440.065 // Earth radius in NM

    val φ1 = Math.toRadians(fromLat)
    val λ1 = Math.toRadians(fromLon)
    val φ3 = Math.toRadians(curLat)
    val λ3 = Math.toRadians(curLon)

    // Angular distance start->current
    val Δφ = φ3 - φ1
    val Δλ = λ3 - λ1
    val a = sin(Δφ / 2) * sin(Δφ / 2) +
            cos(φ1) * cos(φ3) * sin(Δλ / 2) * sin(Δλ / 2)
    val d13 = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))

    val θ13 = Math.toRadians(bearingDeg(fromLat, fromLon, curLat, curLon))
    val θ12 = Math.toRadians(bearingDeg(fromLat, fromLon, toLat, toLon))

    val xte = asin(sin(d13) * sin(θ13 - θ12)) * Rnm
    return xte
}

/**
 * Convert XTE (NM) to CDI dots (±5 dots), where needle points TOWARD the course line.
 * That means we invert sign: if aircraft is LEFT of course (xte<0), needle should go RIGHT (dots>0).
 */
private fun cdiDotsFromXteNm(xteNm: Double, fullScaleNm: Double = 2.0): Float {
    val dots = -(xteNm / fullScaleNm) * 5.0
    return dots.coerceIn(-5.0, 5.0).toFloat()
}
private fun directToFeatureCollection(directTo: DirectToPlan): FeatureCollection {
    val f = directTo.from
    val t = directTo.to
    if (f == null || t == null) return FeatureCollection.fromFeatures(emptyArray())

    val line = LineString.fromLngLats(
        listOf(
            Point.fromLngLat(f.lon, f.lat),
            Point.fromLngLat(t.lon, t.lat)
        )
    )
    return FeatureCollection.fromFeatures(arrayOf(Feature.fromGeometry(line)))
}

private fun distanceNm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val Rnm = 3440.065
    val φ1 = Math.toRadians(lat1)
    val φ2 = Math.toRadians(lat2)
    val dφ = Math.toRadians(lat2 - lat1)
    val dλ = Math.toRadians(lon2 - lon1)

    val a = sin(dφ / 2) * sin(dφ / 2) +
            cos(φ1) * cos(φ2) * sin(dλ / 2) * sin(dλ / 2)
    val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    return Rnm * c
}

private fun formatEtaText(distNm: Double, speedKt: Double?): String {
    if (speedKt == null || speedKt < 5.0) return "—"
    val minutes = ((distNm / speedKt) * 60.0).roundToInt().coerceAtLeast(0)
    val hh = minutes / 60
    val mm = minutes % 60
    return "%02d:%02d".format(hh, mm)
}
