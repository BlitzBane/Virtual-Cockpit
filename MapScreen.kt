package com.adityaapte.virtualcockpit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import android.view.View
import kotlin.math.*

// Prefer offline style JSON in assets
private const val STYLE_URL_DARK_ASSET = "asset://styles/offlinedarkstyle.json"
//
//// Online fallback dark style
//private const val STYLE_URL_DARK_ONLINE = "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"
//
//// Last-resort fallback (simple demo style)
//private const val STYLE_URL_LIGHT_FALLBACK = "https://demotiles.maplibre.org/style.json"

private const val AIRCRAFT_ICON_ID = "aircraft_icon"
private const val AIRCRAFT_SRC_ID = "aircraft_src"
private const val AIRCRAFT_LAYER_ID = "aircraft_layer"

private const val TRAIL_SRC_ID = "trail_src"
private const val TRAIL_LAYER_ID = "trail_layer"

private const val AIRPORT_SRC_ID = "airport_src"

// Split airports into dot + label layers
private const val AIRPORT_DOT_LAYER_ID = "airport_dot_layer"
private const val AIRPORT_LABEL_LAYER_ID = "airport_label_layer"

// Airport label behavior thresholds
private const val ZOOM_SHOW_CODES = 6
private const val ZOOM_ALLOW_NAMES = 8
private const val MAX_ONSCREEN_FOR_NAMES = 15

private const val DIRECT_TO_SOURCE_ID = "direct_to_src"
private const val DIRECT_TO_LAYER_ID = "direct_to_layer"


@Composable
fun MapScreen(
    nav: NavData,
    modifier: Modifier = Modifier,
    isActive: Boolean,
    directTo: DirectToPlan,
    navSource: NavSource,
    onSetFrom: (Waypoint) -> Unit,
    onSetTo: (Waypoint) -> Unit,
    onActivateGps: () -> Unit,
    onUseHdg: () -> Unit,
    onClearPlan: () -> Unit,
    onMapViewReady: (org.maplibre.android.maps.MapView) -> Unit
) {

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val repo = remember { AirportRepository.get(ctx) }

    val lat = nav.lat
    val lon = nav.lon
    val track = (nav.trackDeg ?: 0.0).toFloat()
    val toWp = directTo.to
    val dtgNm: Double? = if (lat != null && lon != null && toWp != null) {
        haversineNm(lat, lon, toWp.lat, toWp.lon)
    } else null

    val gsKtForEta: Double? = nav.speedMps?.let { it * 1.943844 }
    val etaText: String? = dtgNm?.let { formatEtaText(it, gsKtForEta) }


    var follow by remember { mutableStateOf(true) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var styleReady by remember { mutableStateOf(false) }

    // Track style used (important for Offline download definition)
    var styleUriUsed by remember { mutableStateOf(STYLE_URL_DARK_ASSET) }

    // Airport refresh job (debounce/cancel)
    var airportsJob by remember { mutableStateOf<Job?>(null) }

    val trail = remember { mutableStateListOf<Point>() }
    val mapRef = remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }

    // Selected airport overlay
    var selectedAirport by remember { mutableStateOf<AirportPopupData?>(null) }
    var selectedAirportExtras by remember { mutableStateOf<AirportExtras?>(null) }
    var selectedAirportExtrasError by remember { mutableStateOf<String?>(null) }
    var selectedAirportLoading by remember { mutableStateOf(false) }
    var zoomUi by remember { mutableStateOf<Double?>(null) }



    Box(modifier = modifier) {

        // Use lifecycle-safe MapView (no manual onCreate/onStart/onResume here)
        val mapView = rememberMapViewWithLifecycle()
        mapViewRef = mapView

        // Notify parent that MapView is ready
        LaunchedEffect(mapView) {
            onMapViewReady(mapView)
        }

        // Cache the MapLibreMap once (so update() doesn't call getMapAsync repeatedly)
        var mapLibre by remember { mutableStateOf<MapLibreMap?>(null) }
        var didInit by remember { mutableStateOf(false) }

        // One-time init for MapLibre
        LaunchedEffect(Unit) {
            if (didInit) return@LaunchedEffect
            didInit = true

            mapView.getMapAsync { map ->
                mapLibre = map
                mapRef.value = map


                map.uiSettings.apply {
                    isCompassEnabled = true
                    isRotateGesturesEnabled = true
                    isZoomGesturesEnabled = true
                    isScrollGesturesEnabled = true
                }

                fun applyStyle(uri: String) {
                    styleReady = false
                    styleUriUsed = uri
                    map.setStyle(Style.Builder().fromUri(uri)) { style ->
                        setupStyle(
                            ctx = ctx,
                            scope = scope,
                            map = map,
                            style = style,
                            nav = nav,
                            repo = repo,
                            airportsJobState = { airportsJob },
                            setAirportsJobState = { airportsJob = it },
                            setStyleReady = { styleReady = it },
                            onAirportSelected = { popup, icaoProp ->
                                selectedAirport = popup
                                selectedAirportExtras = null
                                selectedAirportExtrasError = null
                                selectedAirportLoading = true

                                scope.launch {
                                    try {
                                        selectedAirportExtras = AirportAssetsDetails.load(ctx, icaoProp)
                                    } catch (e: Exception) {
                                        selectedAirportExtrasError =
                                            e.message ?: "Failed to load airport details"
                                    } finally {
                                        selectedAirportLoading = false
                                    }
                                }
                            }
                        )
                        // --- Direct-To line source + layer (magenta) ---
                        if (style.getSource(DIRECT_TO_SOURCE_ID) == null) {
                            style.addSource(GeoJsonSource(DIRECT_TO_SOURCE_ID, directToFeatureCollection(directTo)))
                        }
                        if (style.getLayer(DIRECT_TO_LAYER_ID) == null) {
                            val layer = LineLayer(DIRECT_TO_LAYER_ID, DIRECT_TO_SOURCE_ID).withProperties(
                                lineColor("#FF00FF"),
                                lineWidth(4f),
                                lineCap("round"),
                                lineJoin("round")
                            )
                            // Put above base map; if you have an airports layer id, place it below/above as needed
                            style.addLayerBelow(layer, AIRCRAFT_LAYER_ID)

                        }

                    }
                }

                // Style fallback chain (asset -> online -> light fallback)
                var styleLoadToken = 0
                fun tryStyleChain() {
                    styleLoadToken++
                    val token = styleLoadToken

                    // 1) asset style
                    applyStyle(STYLE_URL_DARK_ASSET)

                    scope.launch {
                        kotlinx.coroutines.delay(1200)
                        if (token != styleLoadToken) return@launch
//                        if (!styleReady) {
//                            // 2) online dark
//                            applyStyle(STYLE_URL_DARK_ONLINE)
//
//                            kotlinx.coroutines.delay(1500)
//                            if (token != styleLoadToken) return@launch
//                            if (!styleReady) {
//                                // 3) last resort
//                                applyStyle(STYLE_URL_LIGHT_FALLBACK)
//                            }
//                        }
                    }
                }

                tryStyleChain()
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { mv ->
                // KEY FIX: SurfaceView must be hidden via View visibility (alpha doesn't work reliably)
                mv.visibility = if (isActive) View.VISIBLE else View.GONE
                if (!isActive) return@AndroidView

                val map = mapLibre ?: return@AndroidView
                if (!styleReady || lat == null || lon == null) return@AndroidView
                zoomUi = map.cameraPosition.zoom


                val p = Point.fromLngLat(lon, lat)
                if (trail.isEmpty() || distanceOk(trail.last(), p)) {
                    trail.add(p)
                    while (trail.size > 800) trail.removeAt(0)
                }

                val style = map.style ?: return@AndroidView

                style.getSourceAs<GeoJsonSource>(AIRCRAFT_SRC_ID)?.setGeoJson(
                    Feature.fromGeometry(p).also { f ->
                        f.addNumberProperty("bearing", track.toDouble())
                    }
                )

                style.getSourceAs<GeoJsonSource>(TRAIL_SRC_ID)?.setGeoJson(
                    Feature.fromGeometry(LineString.fromLngLats(trail))
                )

                if (follow) {
                    map.easeCamera(
                        CameraUpdateFactory.newLatLng(LatLng(lat, lon)),
                        250
                    )
                }
            }
        )

        LaunchedEffect(directTo, styleReady) {
            val map = mapRef.value ?: return@LaunchedEffect
            if (!styleReady) return@LaunchedEffect
            val style = map.style ?: return@LaunchedEffect

            val src = style.getSourceAs<GeoJsonSource>(DIRECT_TO_SOURCE_ID) ?: return@LaunchedEffect
            src.setGeoJson(directToFeatureCollection(directTo))
        }



        // Overlay controls + airport details overlay
        if (isActive) {
            Box(Modifier.fillMaxSize()) {

                // Follow toggle
                ControlChip(
                    label = if (follow) "FOLLOW: ON" else "FOLLOW: OFF",
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                ) { follow = !follow }

                // Recenter
                ControlChip(
                    label = "RECENTER",
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                ) {
                    val mv = mapViewRef ?: return@ControlChip
                    if (lat == null || lon == null) return@ControlChip
                    follow = true
                    mv.getMapAsync { map ->
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 10.5))
                    }
                }

                // Zoom controls
                Column(
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "Z ${zoomUi?.let { String.format("%.1f", it) } ?: "--"}",
                        color = Color(0xFF00E676),
                        modifier = Modifier
                            .background(Color(0xCC0B0F14))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                    ControlChip(label = " + ", modifier = Modifier) {
                        mapViewRef?.getMapAsync { map -> map.animateCamera(CameraUpdateFactory.zoomIn()) }
                    }
                    ControlChip(label = " - ", modifier = Modifier) {
                        mapViewRef?.getMapAsync { map -> map.animateCamera(CameraUpdateFactory.zoomOut()) }
                    }
                }

                // DTG / ETA (only when a plan destination exists + we have GPS position)
                if (dtgNm != null && etaText != null) {
                    ControlChip(
                        label = "DTG ${"%.1f".format(dtgNm)}NM  ETA $etaText",
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                    ) { /* no-op */ }
                }

                // Airport details full-page overlay
                if (selectedAirport != null) {
                    val a = selectedAirport!!
                    val extras = selectedAirportExtras
                    val err = selectedAirportExtrasError
                    val loading = selectedAirportLoading

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xCC000000))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF0B0F14))
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = buildString {
                                        if (a.code.isNotBlank()) append(a.code).append("  ")
                                        append(a.name.ifBlank { "Airport" })
                                    },
                                    color = Color(0xFFE8EDF2),
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.width(12.dp))

                                Text(
                                    text = "CLOSE",
                                    color = Color(0xFF00E676),
                                    modifier = Modifier
                                        .clickable {
                                            selectedAirport = null
                                            selectedAirportExtras = null
                                            selectedAirportExtrasError = null
                                            selectedAirportLoading = false
                                        }
                                        .padding(8.dp)
                                )
                            }

                            Spacer(Modifier.height(12.dp))
                            if (a.type.isNotBlank()) Text(
                                "Type: ${a.type}",
                                color = Color(0xFFB8C2CC)
                            )
                            Text(
                                "Lat/Lon: ${"%.5f".format(a.lat)}, ${"%.5f".format(a.lon)}",
                                color = Color(0xFFB8C2CC)
                            )
                            Spacer(Modifier.height(12.dp))

                            // ---- Direct-To planner ----
                            val wp = Waypoint(
                                ident = a.code.ifBlank { a.name.take(6).uppercase() },
                                name = a.name.ifBlank { "Airport" },
                                lat = a.lat,
                                lon = a.lon
                            )

                            val fromTxt = directTo.from?.ident ?: "—"
                            val toTxt = directTo.to?.ident ?: "—"
                            val modeTxt = if (navSource == NavSource.GPS && directTo.isActive) "GPS NAV" else "HDG"

                            Text("PLAN: $fromTxt → $toTxt   ($modeTxt)", color = Color(0xFFE8EDF2))
                            Spacer(Modifier.height(8.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ControlChip(label = "SET FROM", modifier = Modifier) { onSetFrom(wp) }
                                ControlChip(label = "SET TO", modifier = Modifier) { onSetTo(wp) }
                            }

                            Spacer(Modifier.height(10.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (directTo.to != null) {
                                    ControlChip(
                                        label = if (navSource == NavSource.GPS && directTo.isActive) "GPS: ON" else "ACTIVATE GPS",
                                        modifier = Modifier
                                    ) { onActivateGps() }
                                }
                                ControlChip(label = "USE HDG", modifier = Modifier) { onUseHdg() }
                                if (directTo.from != null || directTo.to != null) {
                                    ControlChip(label = "CLEAR", modifier = Modifier) { onClearPlan() }
                                }
                            }
                            Spacer(Modifier.height(12.dp))


                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (loading) {
                                    Text(
                                        "Loading runways, frequencies, navaids…",
                                        color = Color(0xFFE8EDF2)
                                    )
                                } else if (err != null) {
                                    Text("Error: $err", color = Color(0xFFFF6B6B))
                                } else {
                                    Text(
                                        "Runways (${extras?.runways?.size ?: 0})",
                                        color = Color(0xFFE8EDF2)
                                    )
                                    val runways = extras?.runways.orEmpty()
                                    if (runways.isEmpty()) {
                                        Text("• None found", color = Color(0xFFB8C2CC))
                                    } else {
                                        runways.take(20).forEach { r ->
                                            Text(
                                                "• ${r.summary()}",
                                                color = Color(0xFFB8C2CC)
                                            )
                                        }
                                        if (runways.size > 20) Text(
                                            "• …and ${runways.size - 20} more",
                                            color = Color(0xFFB8C2CC)
                                        )
                                    }

                                    Text(
                                        "Frequencies (${extras?.freqs?.size ?: 0})",
                                        color = Color(0xFFE8EDF2)
                                    )
                                    val freqs = extras?.freqs.orEmpty()
                                    if (freqs.isEmpty()) {
                                        Text("• None found", color = Color(0xFFB8C2CC))
                                    } else {
                                        freqs.take(30).forEach { f ->
                                            Text(
                                                "• ${f.summary()}",
                                                color = Color(0xFFB8C2CC)
                                            )
                                        }
                                        if (freqs.size > 30) Text(
                                            "• …and ${freqs.size - 30} more",
                                            color = Color(0xFFB8C2CC)
                                        )
                                    }

                                    Text(
                                        "Navaids (${extras?.navaids?.size ?: 0})",
                                        color = Color(0xFFE8EDF2)
                                    )
                                    val navaids = extras?.navaids.orEmpty()
                                    if (navaids.isEmpty()) {
                                        Text("• None found", color = Color(0xFFB8C2CC))
                                    } else {
                                        navaids.take(30).forEach { n ->
                                            Text(
                                                "• ${n.summary()}",
                                                color = Color(0xFFB8C2CC)
                                            )
                                        }
                                        if (navaids.size > 30) Text(
                                            "• …and ${navaids.size - 30} more",
                                            color = Color(0xFFB8C2CC)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

}

private fun setupStyle(
    ctx: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    map: MapLibreMap,
    style: Style,
    nav: NavData,
    repo: AirportRepository,
    airportsJobState: () -> Job?,
    setAirportsJobState: (Job?) -> Unit,
    setStyleReady: (Boolean) -> Unit,
    onAirportSelected: (AirportPopupData, String) -> Unit
) {
    // ---- Icons ----
    style.addImage(AIRCRAFT_ICON_ID, MapIcons.makeAircraftBitmap())

    // ---- Sources ----
    style.addSource(
        GeoJsonSource(
            AIRCRAFT_SRC_ID,
            Feature.fromGeometry(Point.fromLngLat(0.0, 0.0))
        )
    )
    style.addSource(
        GeoJsonSource(
            TRAIL_SRC_ID,
            Feature.fromGeometry(LineString.fromLngLats(emptyList()))
        )
    )
    style.addSource(
        GeoJsonSource(
            AIRPORT_SRC_ID,
            FeatureCollection.fromFeatures(emptyArray())
        )
    )

    // ---- Layers ----
    style.addLayer(
        LineLayer(TRAIL_LAYER_ID, TRAIL_SRC_ID).withProperties(
            lineColor("#00E676"),
            lineWidth(3f),
            lineOpacity(0.85f)
        )
    )

    style.addLayer(
        SymbolLayer(AIRCRAFT_LAYER_ID, AIRCRAFT_SRC_ID).withProperties(
            iconImage(AIRCRAFT_ICON_ID),
            iconAllowOverlap(true),
            iconIgnorePlacement(true),
            iconSize(1.0f),
            iconRotate(get("bearing")),
            iconRotationAlignment("map")
        )
    )

    style.addLayer(
        CircleLayer(AIRPORT_DOT_LAYER_ID, AIRPORT_SRC_ID).withProperties(
            circleColor("#00E676"),
            circleOpacity(0.95f),
            circleStrokeColor("#0B0F14"),
            circleStrokeWidth(1.5f),
            circleRadius(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(3, 2.0),
                    Expression.stop(7, 3.0),
                    Expression.stop(10, 4.0),
                    Expression.stop(13, 6.0),
                    Expression.stop(16, 8.0)
                )
            )
        )
    )

    style.addLayer(
        SymbolLayer(AIRPORT_LABEL_LAYER_ID, AIRPORT_SRC_ID).withProperties(
            textField(get("label")),
            textSize(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(9, 11),
                    Expression.stop(12, 13),
                    Expression.stop(15, 15)
                )
            ),
            textAnchor("top"),
            textOffset(arrayOf(0f, 1.0f)),
            textAllowOverlap(false),
            textIgnorePlacement(false),
            textColor("#E8EDF2"),
            textHaloColor("#0B0F14"),
            textHaloWidth(1.8f)
        ).also { layer ->
            layer.minZoom = ZOOM_SHOW_CODES.toFloat()
        }
    )

    setStyleReady(true)

    // ---- Seed airport DB once (async) ----
    scope.launch(Dispatchers.IO) {
        runCatching { repo.ensureSeededFromAssets() }
    }

    // ---- Set initial view ----
    val startLat = nav.lat ?: 20.5937
    val startLon = nav.lon ?: 78.9629
    map.moveCamera(
        CameraUpdateFactory.newLatLngZoom(LatLng(startLat, startLon), 6.5)
    )

    // ---- Airports refresh on camera idle ----
    fun refreshAirports(m: MapLibreMap) {
        val s = m.style ?: return
        val b = m.projection.visibleRegion.latLngBounds
        val zoom = m.cameraPosition.zoom

        val sw = b.southWest
        val ne = b.northEast

        airportsJobState()?.cancel()
        setAirportsJobState(
            scope.launch {
                val rows = repo.queryVisible(
                    south = sw.latitude,
                    west = sw.longitude,
                    north = ne.latitude,
                    east = ne.longitude,
                    zoom = zoom
                )

                val allowNames =
                    (zoom >= ZOOM_ALLOW_NAMES) && (rows.size < MAX_ONSCREEN_FOR_NAMES)

                val features = rows.map { a ->
                    val iata = a.iata?.trim().orEmpty()
                    val icao = a.icao?.trim().orEmpty()

                    val code = when {
                        iata.isNotBlank() && icao.isNotBlank() -> "$iata / $icao"
                        iata.isNotBlank() -> iata
                        icao.isNotBlank() -> icao
                        else -> ""
                    }

                    val label = when {
                        zoom < ZOOM_SHOW_CODES -> ""
                        allowNames && code.isNotBlank() -> "$code - ${a.name}"
                        allowNames -> a.name
                        code.isNotBlank() -> code
                        else -> a.name
                    }

                    Feature.fromGeometry(Point.fromLngLat(a.lon, a.lat)).also { f ->
                        f.addStringProperty("label", label)
                        f.addStringProperty("name", a.name)
                        if (iata.isNotBlank()) f.addStringProperty("iata", iata)
                        if (icao.isNotBlank()) f.addStringProperty("icao", icao)
                        a.type?.let { f.addStringProperty("type", it) }
                    }
                }

                withContext(Dispatchers.Main) {
                    s.getSourceAs<GeoJsonSource>(AIRPORT_SRC_ID)
                        ?.setGeoJson(FeatureCollection.fromFeatures(features))
                }
            }
        )
    }

    refreshAirports(map)
    map.addOnCameraIdleListener { refreshAirports(map) }

    // ---- Tap airport -> open full-page overlay ----
    map.addOnMapClickListener { latLng ->
        val screenPt = map.projection.toScreenLocation(latLng)
        val hits = map.queryRenderedFeatures(screenPt, AIRPORT_DOT_LAYER_ID)
            .ifEmpty { map.queryRenderedFeatures(screenPt, AIRPORT_LABEL_LAYER_ID) }

        val f = hits.firstOrNull()
        if (f != null) {
            val name = runCatching { f.getStringProperty("name") }.getOrNull().orEmpty()
            val iata = runCatching { f.getStringProperty("iata") }.getOrNull().orEmpty()
            val icaoProp = runCatching { f.getStringProperty("icao") }.getOrNull().orEmpty()
            val type = runCatching { f.getStringProperty("type") }.getOrNull().orEmpty()

            val code = when {
                iata.isNotBlank() -> iata
                icaoProp.isNotBlank() -> icaoProp
                else -> ""
            }

            onAirportSelected(
                AirportPopupData(
                    code = code,
                    name = name,
                    type = type,
                    lat = latLng.latitude,
                    lon = latLng.longitude
                ),
                icaoProp
            )
            true
        } else {
            false
        }
    }
}

private data class AirportPopupData(
    val code: String,
    val name: String,
    val type: String,
    val lat: Double,
    val lon: Double
)

@Composable
private fun ControlChip(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .background(Color(0xCC0B0F14))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, color = Color(0xFF00E676))
    }
}

private fun distanceOk(a: Point, b: Point): Boolean {
    val dx = (a.longitude() - b.longitude())
    val dy = (a.latitude() - b.latitude())
    return (dx * dx + dy * dy) > 1e-10
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

private fun haversineNm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
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